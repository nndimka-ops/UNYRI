//! Device possession is checked only at enrollment, refresh and socket admission.
//! Never log this module's request headers, keys or signing input.
use axum::http::HeaderMap;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ring::signature::{UnparsedPublicKey, ECDSA_P256_SHA256_ASN1};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

use crate::error::AppError;

pub const HEADER: &str = "x-unyri-device-proof";
pub const CLOCK_WINDOW_SECONDS: i64 = 60;
/// Lead decision 19 Nov: 300 s, not 60 s. A 60 s bearer forced a refresh round trip and a hardware
/// signature before the socket on almost every app open; refresh and socket admission stay bound.
pub const BOUND_ACCESS_TTL_SECONDS: i64 = 300;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceKey {
    pub public_key: String,
    pub protection: String,
}

impl std::fmt::Debug for DeviceKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("DeviceKey(<redacted>)")
    }
}

impl DeviceKey {
    pub fn decode(&self) -> Result<Vec<u8>, AppError> {
        if !matches!(
            self.protection.as_str(),
            "secure_enclave" | "keychain" | "strongbox" | "tee"
        ) || self.public_key.len() != 87
        {
            return Err(AppError::SessionRevoked);
        }
        let key = URL_SAFE_NO_PAD
            .decode(&self.public_key)
            .map_err(|_| AppError::SessionRevoked)?;
        if key.len() != 65 || key[0] != 4 {
            return Err(AppError::SessionRevoked);
        }
        Ok(key)
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Proof {
    ts: i64,
    jti: String,
    sig: String,
}

pub fn canonical(
    method: &str,
    target: &str,
    scope: Uuid,
    ts: i64,
    jti: Uuid,
    token: &str,
) -> String {
    format!(
        "UNYRI-DBS-1\n{method}\n{target}\n{scope}\n{ts}\n{jti}\n{}",
        URL_SAFE_NO_PAD.encode(Sha256::digest(token.as_bytes()))
    )
}

/// Authenticate the clock before offering an offset. This prevents unsigned
/// clock hints from becoming an alternate error path for a copied token.
pub fn verify(
    headers: &HeaderMap,
    public_key: &[u8],
    method: &str,
    target: &str,
    scope: Uuid,
    token: &str,
    now: i64,
) -> Result<Uuid, AppError> {
    let mut values = headers.get_all(HEADER).iter();
    let raw = values
        .next()
        .and_then(|v| v.to_str().ok())
        .ok_or(AppError::SessionRevoked)?;
    if values.next().is_some() || raw.len() > 2048 {
        return Err(AppError::SessionRevoked);
    }
    let decoded = URL_SAFE_NO_PAD
        .decode(raw)
        .map_err(|_| AppError::SessionRevoked)?;
    let proof: Proof = serde_json::from_slice(&decoded).map_err(|_| AppError::SessionRevoked)?;
    let jti = Uuid::parse_str(&proof.jti).map_err(|_| AppError::SessionRevoked)?;
    if proof.jti != jti.to_string() {
        return Err(AppError::SessionRevoked);
    }
    let signature = URL_SAFE_NO_PAD
        .decode(&proof.sig)
        .map_err(|_| AppError::SessionRevoked)?;
    UnparsedPublicKey::new(&ECDSA_P256_SHA256_ASN1, public_key)
        .verify(
            canonical(method, target, scope, proof.ts, jti, token).as_bytes(),
            &signature,
        )
        .map_err(|_| AppError::SessionRevoked)?;
    if proof.ts.abs_diff(now) > CLOCK_WINDOW_SECONDS as u64 {
        return Err(AppError::DeviceProofStale(now));
    }
    Ok(jti)
}

pub async fn required(tx: &mut Transaction<'_, Postgres>) -> Result<bool, AppError> {
    Ok(
        sqlx::query_scalar(
            "SELECT device_bound_sessions_required FROM server_policy WHERE id=true",
        )
        .fetch_one(&mut **tx)
        .await?,
    )
}

pub async fn consume(
    tx: &mut Transaction<'_, Postgres>,
    scope: Uuid,
    jti: Uuid,
) -> Result<(), AppError> {
    // Event-driven cleanup only; the index bounds expired-row discovery.
    sqlx::query("DELETE FROM device_proof_replays WHERE (scope_id,jti) IN \
        (SELECT scope_id,jti FROM device_proof_replays WHERE expires_at<clock_timestamp() LIMIT 128 FOR UPDATE SKIP LOCKED)")
        .execute(&mut **tx).await?;
    let inserted = sqlx::query(
        "INSERT INTO device_proof_replays(scope_id,jti,expires_at) \
        VALUES($1,$2,clock_timestamp()+interval '180 seconds') ON CONFLICT DO NOTHING",
    )
    .bind(scope)
    .bind(jti)
    .execute(&mut **tx)
    .await?;
    if inserted.rows_affected() != 1 {
        return Err(AppError::SessionRevoked);
    }
    Ok(())
}

/// Desktop and web clients have no hardware session key yet; required binding applies to phones.
pub fn platform_exempt(platform: &str) -> bool {
    matches!(platform, "desktop" | "web")
}

pub async fn enroll(
    tx: &mut Transaction<'_, Postgres>,
    headers: &HeaderMap,
    key: Option<&DeviceKey>,
    target: &str,
    installation: Uuid,
    exempt: bool,
) -> Result<Option<Vec<u8>>, AppError> {
    let Some(key) = key else {
        if headers.contains_key(HEADER) {
            return Err(AppError::SessionRevoked);
        }
        if !exempt && required(tx).await? {
            return Err(AppError::AppUpdateRequired);
        }
        return Ok(None);
    };
    let public = key.decode()?;
    let jti = verify(
        headers,
        &public,
        "POST",
        target,
        installation,
        "",
        chrono::Utc::now().timestamp(),
    )?;
    consume(tx, installation, jti).await?;
    Ok(Some(public))
}

/// The caller holds the account and session locks, the same order as refresh.
pub async fn refresh(
    tx: &mut Transaction<'_, Postgres>,
    headers: &HeaderMap,
    key: Option<&DeviceKey>,
    session: Uuid,
    token: &str,
    stored: Option<&[u8]>,
    protection: Option<&str>,
    exempt: bool,
) -> Result<bool, AppError> {
    let public = match (stored, key) {
        (Some(stored), Some(key)) => {
            let supplied = key.decode()?;
            if !super::refresh_token::constant_time_eq(stored, &supplied)
                || !super::refresh_token::constant_time_eq(
                    protection.unwrap_or("").as_bytes(),
                    key.protection.as_bytes(),
                )
            {
                return Err(AppError::SessionRevoked);
            }
            stored.to_vec()
        }
        (Some(stored), None) => stored.to_vec(),
        (None, Some(key)) => key.decode()?,
        (None, None) => {
            if headers.contains_key(HEADER) {
                return Err(AppError::SessionRevoked);
            }
            // An unbound phone session under required binding: ask for the update, never revoke
            // (old apps erase local history on session_revoked). A new app enrolls on this refresh.
            if !exempt && required(tx).await? {
                return Err(AppError::AppUpdateRequired);
            }
            return Ok(false);
        }
    };
    let jti = verify(
        headers,
        &public,
        "POST",
        "/auth/refresh",
        session,
        token,
        chrono::Utc::now().timestamp(),
    )?;
    consume(tx, session, jti).await?;
    if stored.is_none() {
        // Pin every ancestor, including consumed parent tokens used for recovery.
        // Account lock serializes enrollment against every other family rotation.
        sqlx::query("UPDATE sessions SET device_public_key=$2,device_key_protection=$3 \
            WHERE family_id=(SELECT family_id FROM sessions WHERE id=$1) AND device_public_key IS NULL")
            .bind(session).bind(&public).bind(&key.ok_or(AppError::SessionRevoked)?.protection)
            .execute(&mut **tx).await?;
    }
    Ok(true)
}

pub async fn socket(
    db: &sqlx::PgPool,
    headers: &HeaderMap,
    session: Uuid,
    token: &str,
) -> Result<(), AppError> {
    let mut tx = db.begin().await?;
    // Serialize legacy admission against first binding. Sockets admitted before
    // that boundary retain their old authorization until their next reconnect.
    let row: Option<(Option<Vec<u8>>, String)> = sqlx::query_as(
        "SELECT s.device_public_key, d.platform::text FROM sessions s JOIN devices d ON d.id=s.device_id \
         WHERE s.id=$1 FOR SHARE OF s",
    )
    .bind(session)
    .fetch_optional(&mut *tx)
    .await?;
    let Some((public, platform)) = row else {
        return Err(AppError::Unauthorized);
    };
    if let Some(public) = public {
        let jti = verify(
            headers,
            &public,
            "GET",
            "ws",
            session,
            token,
            chrono::Utc::now().timestamp(),
        )
        .map_err(socket_error)?;
        consume(&mut tx, session, jti).await.map_err(socket_error)?;
    } else if headers.contains_key(HEADER) || (!platform_exempt(&platform) && required(&mut tx).await?) {
        return Err(AppError::Unauthorized);
    }
    tx.commit().await?;
    Ok(())
}

fn socket_error(error: AppError) -> AppError {
    match error {
        AppError::DeviceProofStale(_) | AppError::Internal => error,
        _ => AppError::Unauthorized,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::{
        rand::SystemRandom,
        signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_ASN1_SIGNING},
    };

    fn new_key() -> EcdsaKeyPair {
        let rng = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng).unwrap();
        EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &rng).unwrap()
    }

    fn proof(key: &EcdsaKeyPair, scope: Uuid, ts: i64, token: &str) -> HeaderMap {
        let jti = Uuid::new_v4();
        let bytes = canonical("POST", "/auth/refresh", scope, ts, jti, token);
        let signature = key.sign(&SystemRandom::new(), bytes.as_bytes()).unwrap();
        let encoded = URL_SAFE_NO_PAD.encode(
            serde_json::to_vec(&serde_json::json!({
                "ts":ts,"jti":jti,"sig":URL_SAFE_NO_PAD.encode(signature.as_ref())
            }))
            .unwrap(),
        );
        let mut headers = HeaderMap::new();
        headers.insert(HEADER, encoded.parse().unwrap());
        headers
    }

    #[test]
    fn proof_context_and_inclusive_clock_contract() {
        let key = new_key();
        let scope = Uuid::new_v4();
        let now = 1_800_000_000;
        for offset in [-60, 0, 60] {
            let headers = proof(&key, scope, now + offset, "token");
            assert!(verify(
                &headers,
                key.public_key().as_ref(),
                "POST",
                "/auth/refresh",
                scope,
                "token",
                now
            )
            .is_ok());
            for (method, target, id, token) in [
                ("GET", "/auth/refresh", scope, "token"),
                ("POST", "ws", scope, "token"),
                ("POST", "/auth/refresh", Uuid::new_v4(), "token"),
                ("POST", "/auth/refresh", scope, "other"),
            ] {
                assert!(matches!(
                    verify(
                        &headers,
                        key.public_key().as_ref(),
                        method,
                        target,
                        id,
                        token,
                        now
                    ),
                    Err(AppError::SessionRevoked)
                ));
            }
        }
        for ts in [now - 61, now + 61, i64::MIN, i64::MAX] {
            let headers = proof(&key, scope, ts, "token");
            assert!(
                matches!(verify(&headers,key.public_key().as_ref(),"POST","/auth/refresh",scope,"token",now),Err(AppError::DeviceProofStale(t)) if t==now)
            );
        }
        let other = new_key();
        let headers = proof(&other, scope, now - 61, "token");
        assert!(matches!(
            verify(
                &headers,
                key.public_key().as_ref(),
                "POST",
                "/auth/refresh",
                scope,
                "token",
                now
            ),
            Err(AppError::SessionRevoked)
        ));
    }

    #[test]
    fn malformed_duplicate_and_oversized_headers_are_terminal() {
        let key = new_key();
        let scope = Uuid::new_v4();
        let now = 1_800_000_000;
        for raw in [
            "%%%".to_string(),
            "a".repeat(2049),
            URL_SAFE_NO_PAD.encode(b"{}"),
        ] {
            let mut headers = HeaderMap::new();
            headers.insert(HEADER, raw.parse().unwrap());
            assert!(matches!(
                verify(
                    &headers,
                    key.public_key().as_ref(),
                    "POST",
                    "/auth/refresh",
                    scope,
                    "token",
                    now
                ),
                Err(AppError::SessionRevoked)
            ));
        }
        let mut headers = proof(&key, scope, now, "token");
        headers.append(HEADER, headers.get(HEADER).unwrap().clone());
        assert!(matches!(
            verify(
                &headers,
                key.public_key().as_ref(),
                "POST",
                "/auth/refresh",
                scope,
                "token",
                now
            ),
            Err(AppError::SessionRevoked)
        ));
    }

    #[test]
    fn canonical_cross_platform_vector() {
        let scope = Uuid::parse_str("00000000-0000-0000-0000-000000000001").unwrap();
        let jti = Uuid::parse_str("00000000-0000-0000-0000-000000000002").unwrap();
        assert_eq!(canonical("POST","/auth/refresh",scope,1800000000,jti,""),
            "UNYRI-DBS-1\nPOST\n/auth/refresh\n00000000-0000-0000-0000-000000000001\n1800000000\n00000000-0000-0000-0000-000000000002\n47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU");
    }

    /// Opt-in timing is a diagnostic, never a machine-dependent pass/fail gate.
    #[test]
    #[ignore]
    fn measure_proof_sign_and_verify_cost() {
        let key = new_key();
        let scope = Uuid::new_v4();
        let now = 1_800_000_000;
        let count = 1000;
        let started = std::time::Instant::now();
        let headers: Vec<_> = (0..count)
            .map(|_| proof(&key, scope, now, "token"))
            .collect();
        let sign = started.elapsed();
        let started = std::time::Instant::now();
        for headers in &headers {
            verify(
                headers,
                key.public_key().as_ref(),
                "POST",
                "/auth/refresh",
                scope,
                "token",
                now,
            )
            .unwrap();
        }
        eprintln!("device proof software diagnostic n={count}: sign+encode={:.3} us/op verify+decode={:.3} us/op",
            sign.as_secs_f64()*1e6/count as f64,started.elapsed().as_secs_f64()*1e6/count as f64);
    }
}

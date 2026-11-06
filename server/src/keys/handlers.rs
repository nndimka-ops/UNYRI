use axum::extract::{Path, State};
use axum::Json;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use uuid::Uuid;

use crate::auth::middleware::AuthUser;
use crate::auth::rate_limit;
use crate::devices::handlers::{
    emit_conversation_security_event_to_chat_partners, EVENT_CONTACT_DEVICE_ADDED,
    EVENT_CONTACT_IDENTITY_CHANGED,
};
use crate::error::AppError;
use crate::keys::models::{
    DeviceBundle, OneTimePreKeyCountResponse, UploadKeysRequest, UploadKeysResponse,
    UserBundlesResponse,
};
use crate::net::ClientIp;
use crate::state::AppState;

const UPLOAD_LIMIT: u64 = 10;
const UPLOAD_WINDOW_SECONDS: u64 = 60;
const MAX_ONE_TIME_PREKEYS_PER_UPLOAD: usize = 200;

fn decode(field: &str, value: &str, limit: usize) -> Result<Vec<u8>, AppError> {
    crate::database_payload::decode_bounded(field,value,limit)
}

/// SHA-256 hex digest of PUBLIC identity material, used only to distinguish
/// genuine later identity transitions in the event_key. Never private key
/// material, never exposed to contacts.
fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    hex::encode(Sha256::digest(bytes))
}

/// `POST /keys` - upload (or replace) this device's public Signal Protocol
/// key material. The server stores these bytes opaquely; it never parses or
/// verifies them (that happens on-device via libsignal when a remote party
/// establishes a session against this bundle).
pub async fn upload_keys(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Json(req): Json<UploadKeysRequest>,
) -> Result<Json<UploadKeysResponse>, AppError> {
    rate_limit::enforce(&state, "upload_keys:ip", addr, 30, UPLOAD_WINDOW_SECONDS).await?;
    rate_limit::enforce(
        &state,
        &format!("upload_keys:device:{}", auth.device_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        UPLOAD_LIMIT,
        UPLOAD_WINDOW_SECONDS,
    )
    .await?;

    if req.one_time_prekeys.len() > MAX_ONE_TIME_PREKEYS_PER_UPLOAD {
        return Err(AppError::Validation(format!(
            "At most {MAX_ONE_TIME_PREKEYS_PER_UPLOAD} one-time prekeys per upload."
        )));
    }

    use crate::database_payload::{MAX_PUBLIC_KEY_BYTES,MAX_KYBER_KEY_BYTES,MAX_SIGNATURE_BYTES,MAX_UNUSED_PREKEYS};
    if req.one_time_prekeys.iter().any(|key|key.key_id<0) {
        return Err(AppError::Validation("One-time prekey IDs must be nonnegative.".into()));
    }
    let identity_key = decode("identity_key", &req.identity_key,MAX_PUBLIC_KEY_BYTES)?;
    let signed_public = decode("signed_prekey.public_key", &req.signed_prekey.public_key,MAX_PUBLIC_KEY_BYTES)?;
    let signed_signature = decode("signed_prekey.signature", &req.signed_prekey.signature,MAX_SIGNATURE_BYTES)?;
    let kyber_public = decode("kyber_prekey.public_key", &req.kyber_prekey.public_key,MAX_KYBER_KEY_BYTES)?;
    let kyber_signature = decode("kyber_prekey.signature", &req.kyber_prekey.signature,MAX_SIGNATURE_BYTES)?;

    let mut one_time = Vec::with_capacity(req.one_time_prekeys.len());
    for otp in &req.one_time_prekeys {
        one_time.push((
            otp.key_id,
            decode("one_time_prekeys[].public_key", &otp.public_key,MAX_PUBLIC_KEY_BYTES)?,
        ));
    }

    let mut tx = state.db.begin().await?;
    // Serialize only this device's key publications. Do not take a standalone
    // users FOR UPDATE lock: security-event fanout also references chat peers.
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended('key-upload:' || $1::uuid::text,0))")
        .bind(auth.device_id).execute(&mut *tx).await?;

    let previous_identity: Option<(Vec<u8>,i32)> =
        sqlx::query_as("SELECT identity_key,one_time_prekey_retired_through FROM device_identity_keys WHERE device_id = $1")
            .bind(auth.device_id)
            .fetch_optional(&mut *tx)
            .await?;

    // A rotated identity (reinstall) invalidates every outstanding one-time
    // prekey: the device no longer holds the private halves, so a peer that is
    // served one of the old prekeys would build an undecryptable prekey
    // message. Clear the stale pool so the fresh upload replaces it.
    let identity_rotated = previous_identity
        .as_ref()
        .map(|(old,_)| old.as_slice() != identity_key.as_slice())
        .unwrap_or(false);
    if identity_rotated {
        sqlx::query("DELETE FROM one_time_prekeys WHERE device_id = $1")
            .bind(auth.device_id)
            .execute(&mut *tx)
            .await?;
    }
    let retired_through = if identity_rotated { -1 } else {
        // Remember exactly the consumed IDs removed by this statement. A
        // concurrent bundle claim cannot be deleted without entering RETURNING.
        let removed: Option<i32> = sqlx::query_scalar("WITH retired AS (DELETE FROM one_time_prekeys WHERE device_id=$1 AND used_at IS NOT NULL RETURNING key_id) SELECT MAX(key_id) FROM retired")
            .bind(auth.device_id).fetch_one(&mut *tx).await?;
        previous_identity.as_ref().map(|(_,floor)|*floor).unwrap_or(-1).max(removed.unwrap_or(-1))
    };

    sqlx::query(
        "INSERT INTO device_identity_keys (device_id, identity_key, registration_id, one_time_prekey_retired_through, updated_at) \
         VALUES ($1, $2, $3, $4, now()) \
         ON CONFLICT (device_id) DO UPDATE SET \
             identity_key = EXCLUDED.identity_key, registration_id = EXCLUDED.registration_id, one_time_prekey_retired_through=EXCLUDED.one_time_prekey_retired_through, updated_at = now()",
    )
    .bind(auth.device_id)
    .bind(&identity_key)
    .bind(req.registration_id)
    .bind(retired_through)
    .execute(&mut *tx)
    .await?;

    sqlx::query(
        "INSERT INTO signed_prekeys (device_id, key_id, public_key, signature, updated_at) \
         VALUES ($1, $2, $3, $4, now()) \
         ON CONFLICT (device_id) DO UPDATE SET \
             key_id = EXCLUDED.key_id, public_key = EXCLUDED.public_key, signature = EXCLUDED.signature, updated_at = now()",
    )
    .bind(auth.device_id)
    .bind(req.signed_prekey.key_id)
    .bind(&signed_public)
    .bind(&signed_signature)
    .execute(&mut *tx)
    .await?;

    sqlx::query(
        "INSERT INTO kyber_prekeys (device_id, key_id, public_key, signature, updated_at) \
         VALUES ($1, $2, $3, $4, now()) \
         ON CONFLICT (device_id) DO UPDATE SET \
             key_id = EXCLUDED.key_id, public_key = EXCLUDED.public_key, signature = EXCLUDED.signature, updated_at = now()",
    )
    .bind(auth.device_id)
    .bind(req.kyber_prekey.key_id)
    .bind(&kyber_public)
    .bind(&kyber_signature)
    .execute(&mut *tx)
    .await?;

    for (key_id, public_key) in &one_time {
        if *key_id<=retired_through { continue; }
        sqlx::query(
            "INSERT INTO one_time_prekeys (device_id, key_id, public_key) VALUES ($1, $2, $3) \
             ON CONFLICT (device_id, key_id) DO NOTHING",
        )
        .bind(auth.device_id)
        .bind(key_id)
        .bind(public_key)
        .execute(&mut *tx)
        .await?;
    }
    let unused: i64=sqlx::query_scalar("SELECT COUNT(*) FROM one_time_prekeys WHERE device_id=$1 AND used_at IS NULL")
        .bind(auth.device_id).fetch_one(&mut *tx).await?;
    if unused>MAX_UNUSED_PREKEYS {
        return Err(AppError::Validation("One-time prekey inventory is full.".into()));
    }

    // Checkpoint C: durable conversation-event emission, in the SAME transaction
    // as the identity write so the cryptographic transition and its recipient
    // rows can never diverge. First-ever identity row -> contact_device_added;
    // a replaced (different) identity key -> contact_identity_changed; the same
    // identity re-uploaded -> no event (and retries dedupe via event_key).
    let transition: Option<(&str, String)> = if previous_identity.is_none() {
        Some((
            EVENT_CONTACT_DEVICE_ADDED,
            format!(
                "{EVENT_CONTACT_DEVICE_ADDED}:{}:{}",
                auth.user_id, auth.device_id
            ),
        ))
    } else if identity_rotated {
        Some((
            EVENT_CONTACT_IDENTITY_CHANGED,
            format!(
                "{EVENT_CONTACT_IDENTITY_CHANGED}:{}:{}:{}",
                auth.user_id,
                auth.device_id,
                sha256_hex(&identity_key),
            ),
        ))
    } else {
        None
    };
    if let Some((event_type, event_key)) = transition {
        emit_conversation_security_event_to_chat_partners(
            &mut tx,
            auth.user_id,
            auth.device_id,
            event_type,
            &event_key,
        )
        .await?;
    }

    crate::database_payload::reserve_device_keys(&mut tx,auth.device_id,auth.user_id).await?;
    tx.commit().await?;

    let (count,): (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM one_time_prekeys WHERE device_id = $1 AND used_at IS NULL",
    )
    .bind(auth.device_id)
    .fetch_one(&state.db)
    .await?;

    Ok(Json(UploadKeysResponse {
        one_time_prekey_count: count,
    }))
}

/// `GET /keys/bundles/{user_id}` - a bundle for every active device of
/// `user_id`, each atomically consuming one one-time prekey (if any remain -
/// `one_time_prekey_id`/`one_time_prekey_public` are omitted otherwise, which
/// is a normal, allowed X3DH/PQXDH fallback, not an error). Devices missing
/// any required key material (never uploaded keys yet) are silently skipped.
pub async fn get_bundles(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Path(user_id): Path<Uuid>,
) -> Result<Json<UserBundlesResponse>, AppError> {
    read_bundles(state, addr, auth, user_id, true, None).await
}

/// Exact device establishment: the SQL filter precedes every prekey claim.
/// No fallback to the batch route is permitted for conference handoffs.
pub async fn get_target_bundle(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Path((user_id, device_id)): Path<(Uuid, Uuid)>,
) -> Result<(axum::http::HeaderMap, Json<UserBundlesResponse>), AppError> {
    let response = read_bundles(state, addr, auth, user_id, true, Some(device_id)).await?;
    if response.0.devices.len() != 1 {
        return Err(AppError::NotFound("Device keys not found.".into()));
    }
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(
        axum::http::header::CACHE_CONTROL,
        axum::http::HeaderValue::from_static("no-store"),
    );
    Ok((headers, response))
}

/// Fresh device/identity discovery for existing Signal sessions. This never
/// claims a one-time prekey; only session establishment uses get_bundles.
pub async fn get_device_bundles(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Path(user_id): Path<Uuid>,
) -> Result<(axum::http::HeaderMap, Json<UserBundlesResponse>), AppError> {
    let response = read_bundles(state, addr, auth, user_id, false, None).await?;
    let mut headers = axum::http::HeaderMap::new();
    headers.insert(
        axum::http::header::CACHE_CONTROL,
        axum::http::HeaderValue::from_static("no-store"),
    );
    Ok((headers, response))
}

async fn read_bundles(
    state: AppState,
    addr: std::net::IpAddr,
    auth: AuthUser,
    user_id: Uuid,
    establish: bool,
    target_device: Option<Uuid>,
) -> Result<Json<UserBundlesResponse>, AppError> {
    crate::users::blocks::require_visible(&state, &auth, user_id).await?;
    if !establish {
        rate_limit::enforce(&state, "keys_devices:ip", addr, 600, 60).await?;
        rate_limit::enforce(
            &state,
            &format!("keys_devices:requester:{}", auth.user_id),
            std::net::Ipv4Addr::UNSPECIFIED.into(),
            300,
            60,
        )
        .await?;
        return discover_devices(&state, user_id, auth.user_id).await;
    } else {
        rate_limit::enforce(&state, "keys_bundles:ip", addr, 120, 60).await?;
        rate_limit::enforce(
            &state,
            &format!("keys_bundles:requester:{}", auth.user_id),
            std::net::Ipv4Addr::UNSPECIFIED.into(),
            60,
            60,
        )
        .await?;
        rate_limit::enforce(
            &state,
            &format!("keys_bundles:pair:{}:{user_id}", auth.user_id),
            std::net::Ipv4Addr::UNSPECIFIED.into(),
            10,
            60,
        )
        .await?;
    }
    // A target-wide budget protects the one-time-prekey pool from being drained
    // by many accounts. Once it is exhausted, do not fail the fetch (that would
    // let one attacker disable key discovery for everyone): serve the signed /
    // Kyber fallback without consuming another one-time prekey. Existing
    // sessions keep working; new sessions use the standard X3DH fallback.
    let consume_one_time = establish
        && rate_limit::try_enforce(
            &state,
            &format!("keys_bundles:target-budget:{user_id}"),
            std::net::Ipv4Addr::UNSPECIFIED.into(),
            30,
            60,
        )
        .await?;
    let devices: Vec<(Uuid, i16)> =
        sqlx::query_as("SELECT id, signal_device_id FROM devices WHERE user_id = $1 AND revoked_at IS NULL AND NOT user_device_blocked(id,$2) AND ($3::uuid IS NULL OR id=$3)")
            .bind(user_id)
            .bind(auth.user_id)
            .bind(target_device)
            .fetch_all(&state.db)
            .await?;

    let mut bundles = Vec::with_capacity(devices.len());
    for (device_id, signal_device_id) in devices {
        let mut tx = state.db.begin().await?;

        let identity: Option<(Vec<u8>, i32)> = sqlx::query_as(
            "SELECT identity_key, registration_id FROM device_identity_keys WHERE device_id = $1",
        )
        .bind(device_id)
        .fetch_optional(&mut *tx)
        .await?;
        let Some((identity_key, registration_id)) = identity else {
            tx.commit().await?;
            continue;
        };

        let signed: Option<(i32, Vec<u8>, Vec<u8>)> = sqlx::query_as(
            "SELECT key_id, public_key, signature FROM signed_prekeys WHERE device_id = $1",
        )
        .bind(device_id)
        .fetch_optional(&mut *tx)
        .await?;
        let Some((signed_prekey_id, signed_prekey_public, signed_prekey_signature)) = signed else {
            tx.commit().await?;
            continue;
        };

        let kyber: Option<(i32, Vec<u8>, Vec<u8>)> = sqlx::query_as(
            "SELECT key_id, public_key, signature FROM kyber_prekeys WHERE device_id = $1",
        )
        .bind(device_id)
        .fetch_optional(&mut *tx)
        .await?;
        let Some((kyber_prekey_id, kyber_prekey_public, kyber_prekey_signature)) = kyber else {
            tx.commit().await?;
            continue;
        };

        // Atomically claim one unused one-time prekey so it can never be
        // handed out twice, even under concurrent bundle fetches. If the
        // target-wide budget is exhausted, deliberately omit it (fallback)
        // rather than denying bundle retrieval.
        let one_time: Option<(i32, Vec<u8>)> = if consume_one_time {
            sqlx::query_as(
                "UPDATE one_time_prekeys SET used_at = now() \
                 WHERE id = ( \
                     SELECT id FROM one_time_prekeys \
                     WHERE device_id = $1 AND used_at IS NULL \
                     ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED \
                 ) \
                 RETURNING key_id, public_key",
            )
            .bind(device_id)
            .fetch_optional(&mut *tx)
            .await?
        } else {
            None
        };

        tx.commit().await?;

        bundles.push(DeviceBundle {
            device_id,
            signal_device_id: signal_device_id as i32,
            registration_id,
            identity_key: STANDARD.encode(identity_key),
            signed_prekey_id,
            signed_prekey_public: STANDARD.encode(signed_prekey_public),
            signed_prekey_signature: STANDARD.encode(signed_prekey_signature),
            kyber_prekey_id,
            kyber_prekey_public: STANDARD.encode(kyber_prekey_public),
            kyber_prekey_signature: STANDARD.encode(kyber_prekey_signature),
            one_time_prekey_id: one_time.as_ref().map(|(id, _)| *id),
            one_time_prekey_public: one_time.map(|(_, key)| STANDARD.encode(key)),
        });
    }

    Ok(Json(UserBundlesResponse { devices: bundles }))
}

/// One database snapshot/round trip, no per-device transactions or prekey writes.
async fn discover_devices(
    state: &AppState,
    user_id: Uuid,
    requester: Uuid,
) -> Result<Json<UserBundlesResponse>, AppError> {
    #[derive(sqlx::FromRow)]
    struct Discovery {
        device_id: Uuid,
        signal_device_id: i16,
        registration_id: i32,
        identity_key: Vec<u8>,
        signed_prekey_id: i32,
        signed_prekey_public: Vec<u8>,
        signed_prekey_signature: Vec<u8>,
        kyber_prekey_id: i32,
        kyber_prekey_public: Vec<u8>,
        kyber_prekey_signature: Vec<u8>,
    }
    let rows: Vec<Discovery> = sqlx::query_as(
        "SELECT d.id AS device_id,d.signal_device_id,i.registration_id,i.identity_key, \
         s.key_id AS signed_prekey_id,s.public_key AS signed_prekey_public,s.signature AS signed_prekey_signature, \
         k.key_id AS kyber_prekey_id,k.public_key AS kyber_prekey_public,k.signature AS kyber_prekey_signature \
         FROM devices d JOIN device_identity_keys i ON i.device_id=d.id \
         JOIN signed_prekeys s ON s.device_id=d.id JOIN kyber_prekeys k ON k.device_id=d.id \
         WHERE d.user_id=$1 AND d.revoked_at IS NULL AND NOT user_device_blocked(d.id,$2) ORDER BY d.id"
    ).bind(user_id).bind(requester).fetch_all(&state.db).await?;
    let devices = rows
        .into_iter()
        .map(|row| DeviceBundle {
            device_id: row.device_id,
            signal_device_id: row.signal_device_id as i32,
            registration_id: row.registration_id,
            identity_key: STANDARD.encode(row.identity_key),
            signed_prekey_id: row.signed_prekey_id,
            signed_prekey_public: STANDARD.encode(row.signed_prekey_public),
            signed_prekey_signature: STANDARD.encode(row.signed_prekey_signature),
            kyber_prekey_id: row.kyber_prekey_id,
            kyber_prekey_public: STANDARD.encode(row.kyber_prekey_public),
            kyber_prekey_signature: STANDARD.encode(row.kyber_prekey_signature),
            one_time_prekey_id: None,
            one_time_prekey_public: None,
        })
        .collect();
    Ok(Json(UserBundlesResponse { devices }))
}

/// Lets a client decide when to replenish its one-time prekey pool.
pub async fn one_time_prekey_count(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<OneTimePreKeyCountResponse>, AppError> {
    let (count,): (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM one_time_prekeys WHERE device_id = $1 AND used_at IS NULL",
    )
    .bind(auth.device_id)
    .fetch_one(&state.db)
    .await?;
    Ok(Json(OneTimePreKeyCountResponse { count }))
}

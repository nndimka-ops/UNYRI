//! Ed25519 possession proofs; never accepts a paper key or private seed.
use super::*;
use crate::net::ClientIp;
use ring::signature::{UnparsedPublicKey, ED25519};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ChallengeRequest {
    purpose: String,
    public_key: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Proof {
    challenge_id: Uuid,
    signature: String,
    #[serde(default)]
    new_signature: Option<String>,
}

fn public(value: &str) -> Result<Vec<u8>, AppError> {
    let bytes = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| AppError::InvalidCredentials)?;
    if bytes.len() != 32 || URL_SAFE_NO_PAD.encode(&bytes) != value {
        return Err(AppError::InvalidCredentials);
    }
    Ok(bytes)
}
pub fn payload(
    server: Uuid,
    user: Uuid,
    session: Uuid,
    id: Uuid,
    purpose: &str,
    public: &[u8],
    nonce: &str,
) -> String {
    format!(
        "COCO-ADMIN-PROOF-V1\n{server}\n{user}\n{session}\n{id}\n{purpose}\n{}\n{nonce}",
        URL_SAFE_NO_PAD.encode(public)
    )
}
fn verify(key: &[u8], message: &str, signature: &str) -> Result<(), AppError> {
    let bytes = URL_SAFE_NO_PAD
        .decode(signature)
        .map_err(|_| AppError::InvalidCredentials)?;
    if bytes.len() != 64 {
        return Err(AppError::InvalidCredentials);
    }
    UnparsedPublicKey::new(&ED25519, key)
        .verify(message.as_bytes(), &bytes)
        .map_err(|_| AppError::InvalidCredentials)
}
pub async fn challenge(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Json(body): Json<ChallengeRequest>,
) -> Result<Json<Value>, AppError> {
    privileged(&state, &auth).await?;
    rate_limit::enforce(&state, "admin_key_challenge", ip, 30, 300).await?;
    rate_limit::enforce(
        &state,
        &format!("admin_key_challenge:{}", auth.user_id),
        std::net::Ipv4Addr::LOCALHOST.into(),
        20,
        300,
    )
    .await?;
    if !["unlock", "rotate", "authorize", "enroll"].contains(&body.purpose.as_str()) {
        return Err(AppError::InvalidCredentials);
    }
    let proposed = public(&body.public_key)?;
    let id = Uuid::new_v4();
    let nonce = random_token();
    let mut tx = state.db.begin().await?;
    let existing: Option<Vec<u8>> = sqlx::query_scalar(
        "SELECT signing_public_key FROM server_administrators WHERE user_id=$1 FOR UPDATE",
    )
    .bind(auth.user_id)
    .fetch_one(&mut *tx)
    .await?;
    if ["unlock", "authorize"].contains(&body.purpose.as_str())
        && existing.as_ref().is_some_and(|v| v != &proposed)
        || ["rotate", "authorize"].contains(&body.purpose.as_str()) && existing.is_none()
        || body.purpose == "enroll" && existing.is_some()
    {
        return Err(AppError::InvalidCredentials);
    }
    let server: Uuid =
        sqlx::query_scalar("SELECT server_id FROM admin_server_identity WHERE singleton")
            .fetch_one(&mut *tx)
            .await?;
    sqlx::query("DELETE FROM admin_key_challenges WHERE expires_at<=now()")
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO admin_key_challenges(id,user_id,auth_session,purpose,public_key,nonce) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(user_id,auth_session) DO UPDATE SET id=excluded.id,purpose=excluded.purpose,public_key=excluded.public_key,nonce=excluded.nonce,expires_at=excluded.expires_at")
        .bind(id).bind(auth.user_id).bind(auth.session_id).bind(&body.purpose).bind(&proposed).bind(&nonce).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(
        json!({"challenge_id":id,"server_id":server,"user_id":auth.user_id,"auth_session":auth.session_id,"purpose":body.purpose,"public_key":body.public_key,"nonce":nonce}),
    ))
}

// Caller holds the administrator row lock. Challenge consumption, enrollment,
// OTP/recovery consumption and session creation commit together or not at all.
pub async fn consume(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    auth: &AuthUser,
    purpose: &str,
    proof: &Proof,
) -> Result<Vec<u8>, AppError> {
    let row:Option<(Vec<u8>,String)>=sqlx::query_as("DELETE FROM admin_key_challenges WHERE id=$1 AND user_id=$2 AND auth_session=$3 AND purpose=$4 AND expires_at>clock_timestamp() RETURNING public_key,nonce")
        .bind(proof.challenge_id).bind(auth.user_id).bind(auth.session_id).bind(purpose).fetch_optional(&mut **tx).await?;
    let (proposed, nonce) = row.ok_or(AppError::InvalidCredentials)?;
    let existing: Option<Vec<u8>> =
        sqlx::query_scalar("SELECT signing_public_key FROM server_administrators WHERE user_id=$1")
            .bind(auth.user_id)
            .fetch_one(&mut **tx)
            .await?;
    let server: Uuid =
        sqlx::query_scalar("SELECT server_id FROM admin_server_identity WHERE singleton")
            .fetch_one(&mut **tx)
            .await?;
    let message = payload(
        server,
        auth.user_id,
        auth.session_id,
        proof.challenge_id,
        purpose,
        &proposed,
        &nonce,
    );
    if ["unlock", "authorize", "enroll"].contains(&purpose) {
        if purpose == "authorize" && existing.is_none() || purpose == "enroll" && existing.is_some()
        {
            return Err(AppError::InvalidCredentials);
        }
        if existing.as_ref().is_some_and(|v| v != &proposed) {
            return Err(AppError::InvalidCredentials);
        }
        verify(&proposed, &message, &proof.signature)?;
    } else {
        verify(
            existing.as_ref().ok_or(AppError::InvalidCredentials)?,
            &message,
            &proof.signature,
        )?;
        verify(
            &proposed,
            &message,
            proof
                .new_signature
                .as_ref()
                .ok_or(AppError::InvalidCredentials)?,
        )?;
    }
    sqlx::query("UPDATE server_administrators SET signing_public_key=$2 WHERE user_id=$1")
        .bind(auth.user_id)
        .bind(&proposed)
        .execute(&mut **tx)
        .await?;
    Ok(proposed)
}
pub async fn rotate(
    State(state): State<AppState>,
    auth: AuthUser,
    admin: Admin,
    Json(proof): Json<Proof>,
) -> Result<Json<Value>, AppError> {
    let mut tx = state.db.begin().await?;
    sqlx::query("SELECT user_id FROM server_administrators WHERE user_id=$1 FOR UPDATE")
        .bind(auth.user_id)
        .fetch_one(&mut *tx)
        .await?;
    active_actor_after_wait(&mut tx, &auth).await?;
    // Revalidate the administrative session under the same lock as rotation.
    let valid:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM admin_sessions s JOIN server_administrators a ON a.user_id=s.user_id WHERE s.token_hash=$1 AND s.user_id=$2 AND s.auth_session=$3 AND s.auth_revision=a.auth_revision AND a.auth_policy IN ('paper_key','both') AND s.signing_public_key=a.signing_public_key AND s.last_used>clock_timestamp()-interval '12 hours' AND s.created_at>clock_timestamp()-interval '12 hours')")
        .bind(admin.token_hash).bind(auth.user_id).bind(auth.session_id).fetch_one(&mut *tx).await?;
    if !valid {
        return Err(denied());
    }
    consume(&mut tx, &auth, "rotate", &proof).await?;
    sqlx::query("UPDATE server_administrators SET auth_revision=auth_revision+1,pending_secret=NULL,pending_session=NULL,pending_expires=NULL,pending_policy=NULL,pending_revision=NULL WHERE user_id=$1")
        .bind(auth.user_id).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM admin_key_challenges WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO admin_audit(actor,action) VALUES($1,'rotate-signing-key')")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::{Ed25519KeyPair, KeyPair};
    #[test]
    fn accepts_cross_platform_vector() {
        let key = public("Szyj6KzonzQXpYgcyGK7U2ECJMcry7zql3_gC1muVPM").unwrap();
        assert!(verify(&key,"COCO test vector","s5Xn31303SSBmTWdT7SJe_ILhCOcveaT1V5o93jjF9TLbo0w2RA380ktPVPks7KyEYAPh5So4RLotc04qgZ3AQ").is_ok());
    }
    #[test]
    fn proofs_bind_context_and_reject_other_keys() {
        let key = Ed25519KeyPair::from_seed_unchecked(&[7; 32]).unwrap();
        let other = Ed25519KeyPair::from_seed_unchecked(&[8; 32]).unwrap();
        let id = Uuid::nil();
        let message = payload(id, id, id, id, "unlock", key.public_key().as_ref(), "nonce");
        let signed = URL_SAFE_NO_PAD.encode(key.sign(message.as_bytes()).as_ref());
        assert!(verify(key.public_key().as_ref(), &message, &signed).is_ok());
        assert!(verify(other.public_key().as_ref(), &message, &signed).is_err());
        assert!(verify(
            key.public_key().as_ref(),
            &message.replace("unlock", "rotate"),
            &signed
        )
        .is_err());
        assert!(verify(
            key.public_key().as_ref(),
            &message.replace("nonce", "other"),
            &signed
        )
        .is_err());
        assert!(verify(key.public_key().as_ref(), &message, "bad").is_err());
    }
}

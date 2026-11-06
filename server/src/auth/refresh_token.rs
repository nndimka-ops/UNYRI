use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use rand::RngCore;
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub struct IssuedRefreshToken {
    pub token: String,
    pub hash: String,
}

/// Opaque refresh token shaped `<session_id>.<random-secret>`. The session id
/// prefix lets a refresh request look up the session row by primary key
/// before hashing, instead of scanning every stored hash. Only the hash is
/// ever persisted (see Этап A `sessions.refresh_token_hash`).
pub fn generate(session_id: Uuid) -> IssuedRefreshToken {
    let mut secret_bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut secret_bytes);
    let secret = URL_SAFE_NO_PAD.encode(secret_bytes);
    let token = format!("{session_id}.{secret}");
    let hash = hash_token(&token);
    IssuedRefreshToken { token, hash }
}

/// Reconstruct a rotation response only with BOTH the original credential and
/// the client's separately generated retry secret. Domain separation prevents
/// use of the signing key's MAC in any other protocol. No recoverable refresh
/// secret is stored in the database.
pub fn for_retry(session_id: Uuid, old_token: &str, request_id: Uuid, signing_secret: &str) -> IssuedRefreshToken {
    use hmac::{Hmac, Mac};
    let mut mac = Hmac::<Sha256>::new_from_slice(signing_secret.as_bytes()).expect("HMAC accepts any key length");
    mac.update(b"COCO refresh retry v1\0");
    mac.update(old_token.as_bytes());
    mac.update(request_id.as_bytes());
    mac.update(session_id.as_bytes());
    let secret = URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes());
    let token = format!("{session_id}.{secret}");
    IssuedRefreshToken { hash: hash_token(&token), token }
}

pub fn hash_token(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    to_hex(&digest)
}

/// Extracts the session id prefix from a presented token without trusting
/// it; the caller must still verify the token's hash against the stored one.
pub fn parse_session_id(token: &str) -> Option<Uuid> {
    let (prefix, _) = token.split_once('.')?;
    Uuid::parse_str(prefix).ok()
}

pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

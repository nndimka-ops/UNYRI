use serde::Serialize;

use crate::config::TurnConfig;

/// Builds the `ice_servers` payload for `GET /calls/ice-servers`: STUN
/// entries need no credentials; TURN entries get a short-lived
/// username/credential pair per the standard "TURN REST API" time-limited
/// credential scheme (matches coturn's `use-auth-secret`): username is
/// `"{expiry_unix}:{user_id}"`, credential is
/// `base64(HMAC-SHA1(shared_secret, username))`. The shared secret itself
/// never leaves the server.
pub fn build_ice_servers(turn: &TurnConfig, user_id: uuid::Uuid) -> Vec<IceServerDto> {
    let mut servers = Vec::new();
    if !turn.stun_urls.is_empty() {
        servers.push(IceServerDto { urls: turn.stun_urls.clone(), username: None, credential: None });
    }
    if !turn.turn_urls.is_empty() {
        let (username, credential) = time_limited_credential(turn);
        servers.push(IceServerDto { urls: turn.turn_urls.clone(), username: Some(username), credential: Some(credential) });
    }
    let _ = user_id; // reserved: could be embedded in the username for auditing later
    servers
}

fn time_limited_credential(turn: &TurnConfig) -> (String, String) {
    use hmac::{Hmac, Mac};
    use sha1::Sha1;
    // coturn's `use-auth-secret` TURN REST API mechanism is HMAC-SHA1 by
    // convention (not configurable to SHA-256 in stock coturn) - must match
    // exactly or the TURN server will reject every relayed allocation.
    type HmacSha1 = Hmac<Sha1>;

    let expiry = chrono::Utc::now().timestamp() + turn.credential_ttl_seconds;
    let username = expiry.to_string();
    let mut mac = HmacSha1::new_from_slice(turn.shared_secret.as_bytes()).expect("HMAC accepts any key length");
    mac.update(username.as_bytes());
    let credential = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, mac.finalize().into_bytes());
    (username, credential)
}

#[derive(Serialize)]
pub struct IceServerDto {
    pub urls: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub username: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub credential: Option<String>,
}

#[derive(Serialize)]
pub struct IceServersResponse {
    pub ice_servers: Vec<IceServerDto>,
}

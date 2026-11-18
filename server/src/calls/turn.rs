use serde::Serialize;

use crate::config::TurnConfig;

/// Builds the `ice_servers` payload for `GET /calls/ice-servers`: STUN
/// entries need no credentials; TURN entries get a short-lived
/// username/credential pair per the standard "TURN REST API" time-limited
/// credential scheme (matches coturn's `use-auth-secret`): username is
/// `"{expiry_unix}:{user_id}"`, credential is
/// `base64(HMAC-SHA1(shared_secret, username))`. The shared secret itself
/// never leaves the server.
///
/// The user id suffix ties every relay allocation in coturn's logs to an
/// account (coturn takes the digits before `:` as the expiry and signs the
/// whole string). coturn checks the expiry when a TURN session first
/// authenticates; later refreshes on that allocation reuse the session key,
/// so an established relay outlives the expiry. New allocations (an ICE
/// restart) need unexpired credentials: clients should refetch at
/// `ttl_seconds / 2`.
pub fn build_ice_servers(turn: &TurnConfig, user_id: uuid::Uuid) -> Vec<IceServerDto> {
    build_ice_servers_at(turn, user_id, chrono::Utc::now().timestamp())
}

fn build_ice_servers_at(turn: &TurnConfig, user_id: uuid::Uuid, now: i64) -> Vec<IceServerDto> {
    let mut servers = Vec::new();
    if !turn.stun_urls.is_empty() {
        servers.push(IceServerDto { urls: turn.stun_urls.clone(), username: None, credential: None });
    }
    if !turn.turn_urls.is_empty() {
        let (username, credential) = time_limited_credential(turn, user_id, now);
        servers.push(IceServerDto { urls: turn.turn_urls.clone(), username: Some(username), credential: Some(credential) });
    }
    servers
}

fn time_limited_credential(turn: &TurnConfig, user_id: uuid::Uuid, now: i64) -> (String, String) {
    use hmac::{Hmac, Mac};
    use sha1::Sha1;
    // coturn's `use-auth-secret` TURN REST API mechanism is HMAC-SHA1 by
    // convention (not configurable to SHA-256 in stock coturn) - must match
    // exactly or the TURN server will reject every relayed allocation.
    type HmacSha1 = Hmac<Sha1>;

    let expiry = now + turn.credential_ttl_seconds;
    let username = format!("{expiry}:{user_id}");
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
    /// Lifetime of the TURN credentials above. Additive field; clients that
    /// ignore it keep working.
    pub ttl_seconds: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn turn_username_is_expiry_then_user_id_and_signs_the_whole_string() {
        use hmac::{Hmac, Mac};
        let turn = TurnConfig {
            stun_urls: vec!["stun:relay.example.com:3478".into()],
            turn_urls: vec!["turn:relay.example.com:3478?transport=udp".into()],
            shared_secret: "unit-test-secret".into(),
            credential_ttl_seconds: 1200,
        };
        let user = uuid::Uuid::new_v4();
        let servers = build_ice_servers_at(&turn, user, 1_000_000);
        assert_eq!(servers.len(), 2);
        assert!(servers[0].username.is_none() && servers[0].credential.is_none());
        let username = servers[1].username.as_deref().unwrap();
        assert_eq!(username, format!("1001200:{user}"));
        let mut mac = Hmac::<sha1::Sha1>::new_from_slice(b"unit-test-secret").unwrap();
        mac.update(username.as_bytes());
        let expected = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, mac.finalize().into_bytes());
        assert_eq!(servers[1].credential.as_deref(), Some(expected.as_str()));
    }
}

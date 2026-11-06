use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Deserialize)]
pub struct SignedPreKeyUpload {
    pub key_id: i32,
    pub public_key: String,
    pub signature: String,
}

#[derive(Debug, Deserialize)]
pub struct KyberPreKeyUpload {
    pub key_id: i32,
    pub public_key: String,
    pub signature: String,
}

#[derive(Debug, Deserialize)]
pub struct OneTimePreKeyUpload {
    pub key_id: i32,
    pub public_key: String,
}

/// All key material is base64-encoded opaque bytes as far as the server is
/// concerned - it never parses, verifies, or otherwise interprets them
/// cryptographically. That verification happens on-device via libsignal
/// when a session is actually established.
#[derive(Debug, Deserialize)]
pub struct UploadKeysRequest {
    pub identity_key: String,
    pub registration_id: i32,
    pub signed_prekey: SignedPreKeyUpload,
    pub kyber_prekey: KyberPreKeyUpload,
    #[serde(default)]
    pub one_time_prekeys: Vec<OneTimePreKeyUpload>,
}

#[derive(Debug, Serialize)]
pub struct UploadKeysResponse {
    pub one_time_prekey_count: i64,
}

#[derive(Debug, Serialize)]
pub struct DeviceBundle {
    pub device_id: Uuid,
    pub signal_device_id: i32,
    pub registration_id: i32,
    pub identity_key: String,
    pub signed_prekey_id: i32,
    pub signed_prekey_public: String,
    pub signed_prekey_signature: String,
    pub kyber_prekey_id: i32,
    pub kyber_prekey_public: String,
    pub kyber_prekey_signature: String,
    pub one_time_prekey_id: Option<i32>,
    pub one_time_prekey_public: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct UserBundlesResponse {
    pub devices: Vec<DeviceBundle>,
}

#[derive(Debug, Serialize)]
pub struct OneTimePreKeyCountResponse {
    pub count: i64,
}

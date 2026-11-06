use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Mirrors the `device_platform` Postgres enum from the Этап A migration.
#[derive(Debug, Clone, Copy, sqlx::Type, Serialize, Deserialize)]
#[sqlx(type_name = "device_platform", rename_all = "lowercase")]
#[serde(rename_all = "lowercase")]
pub enum DevicePlatform {
    Android,
    Ios,
    Desktop,
    Web,
}

#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub username: String,
    pub password: String,
    pub display_name: String,
    /// Stable, app-generated INSTALLATION identifier (not a hardware
    /// fingerprint). One installation may be used by different accounts over
    /// time - each account gets its own account-scoped device row.
    pub installation_id: Uuid,
    #[serde(default)]
    pub installation_credential: Option<String>,
    pub device_platform: DevicePlatform,
    pub device_name: Option<String>,
    /// Display-only, client-supplied, untrusted metadata (Этап L.2).
    pub os_version: Option<String>,
    pub app_version: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
    pub installation_id: Uuid,
    #[serde(default)]
    pub installation_credential: Option<String>,
    pub device_platform: DevicePlatform,
    pub device_name: Option<String>,
    /// Display-only, client-supplied, untrusted metadata (Этап L.2).
    pub os_version: Option<String>,
    pub app_version: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct RefreshRequest {
    pub refresh_token: String,
    #[serde(default)]
    pub request_id: Option<Uuid>,
}

#[derive(Debug, Serialize)]
pub struct UserPublic {
    pub id: Uuid,
    pub username: String,
    pub display_name: String,
}

#[derive(Debug, Serialize)]
pub struct AuthResponse {
    /// Snapshot of this account's existing history at authentication time.
    /// Clients must not use this as a sync/read cursor or discard ciphertext.
    pub history_before_sequence: i64,
    pub access_token: String,
    pub access_token_expires_at: DateTime<Utc>,
    pub refresh_token: String,
    pub device_id: Uuid,
    pub user: UserPublic,
}

#[derive(Debug, Serialize)]
pub struct RefreshResponse {
    pub access_token: String,
    pub access_token_expires_at: DateTime<Utc>,
    pub refresh_token: String,
}

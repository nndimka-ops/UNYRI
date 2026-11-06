use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::auth::models::DevicePlatform;

/// One device of the authenticated user, as returned by `GET /devices`
/// (active devices only - revoked devices are surfaced via the security
/// event history, never this list).
#[derive(Debug, Serialize)]
pub struct DeviceDto {
    pub id: Uuid,
    pub display_name: String,
    pub platform: DevicePlatform,
    /// Display-only, client-supplied, untrusted metadata (L.2/L.3).
    pub os_version: Option<String>,
    pub app_version: Option<String>,
    /// Coalesced last-activity timestamp (login / token refresh / WS connect).
    pub last_active_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    /// True when this is the device the current request is authenticated as.
    pub is_current: bool,
}

#[derive(Debug, Serialize)]
pub struct DeviceListResponse {
    pub devices: Vec<DeviceDto>,
}

#[derive(Debug, Deserialize)]
pub struct RenameDeviceRequest {
    /// User-editable label (e.g. "Bob's phone"). May be empty -> resets to
    /// the platform default name. Length is validated server-side.
    pub display_name: String,
}

/// The audience a security event is addressed to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SecurityEventScope {
    /// Owner-facing account security log (existing `new_device`, revokes, ...).
    Account,
    /// A contact's cryptographic state change, shown inside an existing chat.
    Conversation,
}

impl SecurityEventScope {
    pub fn as_str(self) -> &'static str {
        match self {
            SecurityEventScope::Account => "account",
            SecurityEventScope::Conversation => "conversation",
        }
    }
}

/// One server-side security event, newest-first, keyset-paginated by `id`.
#[derive(Debug, Serialize)]
pub struct SecurityEventDto {
    pub id: i64,
    /// `new_device` | `device_revoked` | `all_other_devices_revoked`
    pub event_type: String,
    /// Device that caused the event (the newly-added device, the revoking
    /// device, or NULL for server-initiated events).
    pub actor_device_id: Option<Uuid>,
    /// Device the event is about (the new device, or the revoked device).
    pub subject_device_id: Option<Uuid>,
    /// Non-secret, display-only metadata (platform, name, os/app version).
    pub metadata: serde_json::Value,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize)]
pub struct SecurityEventsResponse {
    pub events: Vec<SecurityEventDto>,
    /// Opaque cursor for the next page; absent/null when there are no more.
    pub next_cursor: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct RevokeOthersResponse {
    /// How many other active devices were revoked.
    pub revoked_count: i64,
}

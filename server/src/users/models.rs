use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Full profile as seen by the account owner (`GET/PATCH /users/me`). Never
/// include password_hash, session/device/refresh-token data here. `avatar_key`
/// is deliberately NOT exposed - it is an internal S3 reference; the client
/// derives a temporary download URL via `GET /users/me/avatar` (or
/// `/users/{id}/avatar`) and treats it as disposable transport.
#[derive(Debug, Serialize)]
pub struct UserProfile {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub admin_role: Option<String>,
    pub id: Uuid,
    pub username: String,
    pub display_name: String,
    pub bio: Option<String>,
    pub avatar_url: Option<String>,
    pub profile_version: i64,
    pub avatar_version: i64,
    pub has_avatar: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// What another user is allowed to see about you (`GET /users/search`).
/// Deliberately narrower than `UserProfile`: no timestamps, no internal ids
/// beyond the public user id, and no `avatar_key` (it stays internal).
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct PublicUser {
    pub id: Uuid,
    pub username: String,
    pub display_name: String,
    pub bio: Option<String>,
    pub avatar_url: Option<String>,
    pub profile_version: i64,
    pub avatar_version: i64,
    pub has_avatar: bool,
}

/// Tri-state field update: a field can be left untouched (absent from the
/// JSON body), explicitly cleared (`null`), or set to a new value. A plain
/// `Option<T>` can't distinguish "absent" from "null", which matters for
/// nullable columns like `avatar_url` (PATCH must support removing it).
#[derive(Debug, Default)]
pub enum Patch<T> {
    #[default]
    Absent,
    Null,
    Value(T),
}

impl<'de, T: Deserialize<'de>> Deserialize<'de> for Patch<T> {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        Option::deserialize(deserializer).map(|opt| match opt {
            Some(v) => Patch::Value(v),
            None => Patch::Null,
        })
    }
}

#[derive(Debug, Deserialize)]
pub struct UpdateProfileRequest {
    pub username: Option<String>,
    pub display_name: Option<String>,
    #[serde(default)]
    pub bio: Patch<String>,
    #[serde(default)]
    pub avatar_url: Patch<String>,
}

#[derive(Debug, Deserialize)]
pub struct SearchQuery {
    pub q: String,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct SearchResponse {
    pub items: Vec<PublicUser>,
    pub next_offset: Option<i64>,
}

/// `POST /users/me/avatar` - a fresh `avatars/{uuid}` object + presigned PUT.
/// Avatar transport is a SEPARATE object prefix from Signal chat attachments
/// (`attachments/`) - never mixed, never encrypted message metadata.
#[derive(Debug, Serialize)]
pub struct CreateAvatarResponse {
    pub object_key: String,
    pub upload_url: String,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CreateAvatarRequest { pub size_bytes: i64 }

/// `GET /users/me/avatar` - presigned GET for the current user's uploaded
/// avatar object.
#[derive(Debug, Serialize)]
pub struct AvatarDownloadResponse {
    pub download_url: String,
    pub expires_at: DateTime<Utc>,
}

/// `POST /users/me/avatar/complete` - commit an already-uploaded object as the
/// authoritative avatar. The server HEAD-verifies the object exists before
/// touching any profile state; the key becomes authoritative only after commit.
#[derive(Debug, Deserialize)]
pub struct CompleteAvatarRequest {
    pub object_key: String,
}


use axum::extract::{Query, State};
use axum::Json;
use chrono::{DateTime, Utc};
use uuid::Uuid;

use crate::net::ClientIp;
use crate::auth::middleware::AuthUser;
use crate::auth::rate_limit;
use crate::error::AppError;
use crate::state::AppState;
use crate::users::models::{AvatarDownloadResponse, CompleteAvatarRequest, CreateAvatarResponse, Patch, PublicUser, SearchQuery, SearchResponse, UpdateProfileRequest, UserProfile};
use crate::validation::{is_unique_violation, validate_avatar_url, validate_bio, validate_display_name, validate_username};

const SEARCH_QUERY_MIN_CHARS: usize = 2;
const SEARCH_LIMIT_DEFAULT: i64 = 20;
const SEARCH_LIMIT_MAX: i64 = 50;
const SEARCH_RATE_LIMIT: u64 = 30;
const SEARCH_RATE_WINDOW_SECONDS: u64 = 60;

/// A best-effort invalidation, never a second profile store or a reason to fail
/// an already committed mutation. Reconnect clients read GET /users/me again.
async fn notify_profile_changed(state: &AppState, owner: Uuid, version: i64) {
    let devices: Vec<Uuid> = sqlx::query_scalar(
        "SELECT d.id FROM devices d WHERE d.user_id=$1 AND d.revoked_at IS NULL \
         AND EXISTS(SELECT 1 FROM sessions s WHERE s.user_id=$1 AND s.device_id=d.id \
             AND s.revoked_at IS NULL AND s.expires_at>now())",
    ).bind(owner).fetch_all(&state.db).await.unwrap_or_default();
    let hint = serde_json::json!({"type":"profile.changed","user_id":owner,"profile_version":version}).to_string();
    for device in devices { state.ws_registry.send_to_device(device, &hint).await; }
}

#[derive(sqlx::FromRow)]
struct UserProfileRow {
    id: Uuid,
    username: String,
    display_name: String,
    bio: Option<String>,
    avatar_url: Option<String>,
    avatar_key: Option<String>,
    profile_version: i64,
    avatar_version: i64,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

impl From<UserProfileRow> for UserProfile {
    fn from(row: UserProfileRow) -> Self {
        UserProfile {
            admin_role: None,
            id: row.id,
            username: row.username,
            display_name: row.display_name,
            bio: row.bio,
            avatar_url: row.avatar_url,
            profile_version: row.profile_version,
            avatar_version: row.avatar_version,
            has_avatar: row.avatar_key.is_some(),
            created_at: row.created_at,
            updated_at: row.updated_at,
        }
    }
}

#[derive(sqlx::FromRow)]
struct PublicUserRow {
    id: Uuid,
    username: String,
    display_name: String,
    bio: Option<String>,
    avatar_url: Option<String>,
    avatar_key: Option<String>,
    profile_version: i64,
    avatar_version: i64,
}

impl From<PublicUserRow> for PublicUser {
    fn from(row: PublicUserRow) -> Self {
        PublicUser {
            id: row.id,
            username: row.username,
            display_name: row.display_name,
            bio: row.bio,
            avatar_url: row.avatar_url,
            profile_version: row.profile_version,
            avatar_version: row.avatar_version,
            has_avatar: row.avatar_key.is_some(),
        }
    }
}

const PROFILE_COLUMNS: &str = "id, username, display_name, bio, avatar_url, avatar_key, profile_version, avatar_version, created_at, updated_at";

/// `GET /users/me` - identity comes from the validated access token
/// (`AuthUser`), never from a client-supplied id.
pub async fn get_me(State(state): State<AppState>, auth: AuthUser) -> Result<Json<UserProfile>, AppError> {
    let row = sqlx::query_as::<_, UserProfileRow>(&format!(
        "SELECT {PROFILE_COLUMNS} FROM users WHERE id = $1 AND deleted_at IS NULL"
    ))
    .bind(auth.user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or(AppError::Unauthorized)?;

    let mut profile: UserProfile = row.into();
    profile.admin_role = crate::admin::role(&state.db, auth.user_id).await?;
    Ok(Json(profile))
}

/// `PATCH /users/me` - every field is optional; only supplied fields change.
/// Username uniqueness races are resolved by the database itself: we don't
/// pre-check availability, we just attempt the UPDATE and let the unique
/// index on `username_normalized` raise a conflict (SQLSTATE 23505) if a
/// concurrent request already claimed the name, which we map to 409.
///
/// E.1: runs in a transaction and increments `profile_version` exactly once
/// iff a user-visible field actually changed (a no-op PATCH never bumps it),
/// and increments `avatar_version` exactly once iff the authoritative
/// `avatar_key` actually changed (replaced or removed).
pub async fn patch_me(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<UpdateProfileRequest>,
) -> Result<Json<UserProfile>, AppError> {
    if let Some(username) = &req.username {
        validate_username(username)?;
    }
    if let Some(display_name) = &req.display_name {
        validate_display_name(display_name)?;
    }
    if let Patch::Value(bio) = &req.bio {
        validate_bio(bio)?;
    }
    if let Patch::Value(avatar_url) = &req.avatar_url {
        validate_avatar_url(avatar_url)?;
    }

    let (avatar_provided, avatar_value) = match &req.avatar_url {
        Patch::Absent => (false, None::<String>),
        Patch::Null => (true, None),
        Patch::Value(v) => (true, Some(v.clone())),
    };

    // An empty/whitespace bio is a clear (NULL), matching the mobile UX of
    // "leave it blank to remove your bio".
    let (bio_provided, bio_value) = match &req.bio {
        Patch::Absent => (false, None::<String>),
        Patch::Null => (true, None),
        Patch::Value(v) => {
            let trimmed = v.trim();
            if trimmed.is_empty() { (true, None) } else { (true, Some(trimmed.to_string())) }
        }
    };

    // PATCH `avatar_url` remains for JSON compatibility: a value under
    // `avatars/` is our own object -> authoritative `avatar_key`; anything
    // else is a legacy external URL that only ever lives in `avatar_url`.
    let avatar_key_value = avatar_value.as_deref().filter(|v| v.starts_with("avatars/"));
    let avatar_check=if let Some(key)=avatar_key_value {
        Some(super::avatar_uploads::validate_upload(&state,auth.user_id,key).await?)
    } else { None };

    let mut tx = state.db.begin().await?;

    #[derive(sqlx::FromRow)]
    struct Current {
        username: String,
        display_name: String,
        bio: Option<String>,
        avatar_url: Option<String>,
        avatar_key: Option<String>,
        profile_version: i64,
        avatar_version: i64,
    }
    let current: Current = sqlx::query_as(
        "SELECT username, display_name, bio, avatar_url, avatar_key, profile_version, avatar_version \
         FROM users WHERE id = $1 AND deleted_at IS NULL FOR UPDATE",
    )
    .bind(auth.user_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::Unauthorized)?;

    if let (Some(key),Some(expected))=(avatar_key_value,avatar_check) {
        super::avatar_uploads::lock_commit(&mut tx,auth.user_id,key,expected).await?;
    }
    let new_username = req.username.clone().unwrap_or_else(|| current.username.clone());
    let new_display_name = req.display_name.clone().unwrap_or_else(|| current.display_name.clone());
    let new_bio = if bio_provided { bio_value.clone() } else { current.bio.clone() };
    let new_avatar_url = if avatar_provided { avatar_value.clone() } else { current.avatar_url.clone() };
    let new_avatar_key = if avatar_provided { avatar_key_value.map(str::to_string) } else { current.avatar_key.clone() };

    let profile_changed = new_username != current.username
        || new_display_name != current.display_name
        || new_bio != current.bio
        || new_avatar_url != current.avatar_url;
    let avatar_changed = new_avatar_key != current.avatar_key;

    if profile_changed {
        let new_profile_version = current.profile_version + 1;
        let new_avatar_version = if avatar_changed { current.avatar_version + 1 } else { current.avatar_version };
        sqlx::query(
            "UPDATE users SET \
                 username = $1, display_name = $2, bio = $3, avatar_url = $4, avatar_key = $5, \
                 profile_version = $6, avatar_version = $7 \
             WHERE id = $8 AND deleted_at IS NULL",
        )
        .bind(&new_username)
        .bind(&new_display_name)
        .bind(&new_bio)
        .bind(&new_avatar_url)
        .bind(&new_avatar_key)
        .bind(new_profile_version)
        .bind(new_avatar_version)
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await
        .map_err(|err| if is_unique_violation(&err) { AppError::UsernameTaken } else { AppError::from(err) })?;
    }
    let row = sqlx::query_as::<_, UserProfileRow>(&format!(
        "SELECT {PROFILE_COLUMNS} FROM users WHERE id = $1 AND deleted_at IS NULL"
    ))
    .bind(auth.user_id)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;

    if profile_changed { notify_profile_changed(&state, auth.user_id, row.profile_version).await; }
    Ok(Json(row.into()))
}

/// `GET /users/search?q=...&limit=...&offset=...` - authenticated, rate
/// limited, paginated substring search over usernames using the pg_trgm GIN
/// index (see migration 0002). Only ever returns `PublicUser` fields.
pub async fn search(
    State(state): State<AppState>,
    ClientIp(addr): ClientIp,
    auth: AuthUser,
    Query(query): Query<SearchQuery>,
) -> Result<Json<SearchResponse>, AppError> {
    rate_limit::enforce(&state, "search:ip", addr, 120, SEARCH_RATE_WINDOW_SECONDS).await?;
    rate_limit::enforce(
        &state,
        &format!("search:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        SEARCH_RATE_LIMIT,
        SEARCH_RATE_WINDOW_SECONDS,
    ).await?;

    let q = query.q.trim();
    if q.chars().count() < SEARCH_QUERY_MIN_CHARS {
        return Err(AppError::Validation(format!(
            "Search query must be at least {SEARCH_QUERY_MIN_CHARS} characters."
        )));
    }

    let limit = query.limit.unwrap_or(SEARCH_LIMIT_DEFAULT).clamp(1, SEARCH_LIMIT_MAX);
    let offset = query.offset.unwrap_or(0).max(0);

    // Escape ILIKE wildcards in user input so `%`/`_` characters in the
    // search query are treated literally, not as pattern operators.
    let escaped = q.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_");
    let pattern = format!("%{escaped}%");

    // Fetch one extra row to know whether another page exists, without a
    // separate COUNT(*) query.
    let rows: Vec<PublicUserRow> = sqlx::query_as(
        "SELECT id, username, display_name, bio, avatar_url, avatar_key, profile_version, avatar_version \
         FROM users \
         WHERE deleted_at IS NULL AND id <> $4 AND username_normalized ILIKE $1 ESCAPE '\\' \
           AND NOT user_device_blocked($5,users.id) \
         ORDER BY username_normalized \
         LIMIT $2 OFFSET $3",
    )
    .bind(&pattern)
    .bind(limit + 1)
    .bind(offset)
    .bind(auth.user_id)
    .bind(auth.device_id)
    .fetch_all(&state.db)
    .await?;

    let has_more = rows.len() as i64 > limit;
    let items: Vec<PublicUser> = rows.into_iter().take(limit as usize).map(Into::into).collect();
    let next_offset = if has_more { Some(offset + limit) } else { None };

    Ok(Json(SearchResponse { items, next_offset }))
}

/// PROFILE.1: `POST /users/me/avatar` - presigned PUT for a fresh
/// `avatars/{uuid}` object. The client uploads directly to S3, then commits
/// the key via `POST /users/me/avatar/complete` (which HEAD-verifies the
/// object before any profile state changes). The server never proxies avatar
/// bytes and never stores image data in Postgres.
pub async fn create_avatar(
    State(state): State<AppState>,
    auth: AuthUser,
    req: Option<Json<super::models::CreateAvatarRequest>>,
) -> Result<Json<CreateAvatarResponse>, AppError> {
    let Json(req)=req.ok_or_else(||AppError::Validation("Avatar upload requires size_bytes. Update UNYRI and select the image again.".into()))?;
    super::avatar_uploads::validate_size(req.size_bytes)?;
    crate::auth::rate_limit::enforce(&state,&format!("avatar-create:{}",auth.user_id),std::net::Ipv4Addr::UNSPECIFIED.into(),10,60).await?;
    let object_key=super::avatar_uploads::create(&state,&auth,req.size_bytes).await?;
    let (upload_url, expires_at) = state.s3.presign_put_sized(&object_key,req.size_bytes)?;
    Ok(Json(CreateAvatarResponse { object_key, upload_url, expires_at }))
}

/// E.1: `POST /users/me/avatar/complete` - authoritative avatar commit.
///
/// Safe order: the object must already exist in S3 (HEAD-verified), then a
/// single transaction makes `avatar_key` authoritative, bumps `avatar_version`
/// and `profile_version` exactly once, and commits. The previous avatar object
/// is retired by lease-aware cleanup after commit; a cleanup failure never
/// rolls back a committed profile, and the old object is never deleted before the new one
/// is committed. Retrying with the SAME key after a lost response is a no-op
/// (the key is already authoritative, so nothing increments twice).
pub async fn complete_avatar(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(req): Json<CompleteAvatarRequest>,
) -> Result<Json<UserProfile>, AppError> {
    let key = req.object_key;
    if !key.starts_with("avatars/") || key.len() > 512 {
        return Err(AppError::Validation("Avatar key must be an `avatars/` object key.".into()));
    }

    let expected=super::avatar_uploads::validate_upload(&state,auth.user_id,&key).await?;

    let mut tx = state.db.begin().await?;
    let old_key: Option<String> = sqlx::query_scalar(
        "SELECT avatar_key FROM users WHERE id = $1 AND deleted_at IS NULL FOR UPDATE",
    )
    .bind(auth.user_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::Unauthorized)?;

    super::avatar_uploads::lock_commit(&mut tx,auth.user_id,&key,expected).await?;
    let changed = old_key.as_deref() != Some(key.as_str());
    if changed {
        sqlx::query(
            "UPDATE users SET avatar_key = $1, avatar_url = $1, \
                 avatar_version = avatar_version + 1, profile_version = profile_version + 1 \
             WHERE id = $2 AND deleted_at IS NULL",
        )
        .bind(&key)
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    }

    // Capture this mutation's acknowledgement under the same row lock. A
    // different device may replace the avatar immediately after COMMIT.
    let row = sqlx::query_as::<_, UserProfileRow>(&format!(
        "SELECT {PROFILE_COLUMNS} FROM users WHERE id = $1 AND deleted_at IS NULL"
    ))
    .bind(auth.user_id)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;

    // Lease-aware cleanup owns replaced objects. Deleting/releasing them while
    // a PUT capability remains valid would allow an uncharged late re-upload.

    if changed { notify_profile_changed(&state, auth.user_id, row.profile_version).await; }
    Ok(Json(row.into()))
}

/// E.1: `DELETE /users/me/avatar` - authoritative avatar removal. Advances
/// `avatar_version` and `profile_version` only if an avatar actually existed;
/// a no-op delete (already absent) does not create uncontrolled increments.
/// The old object remains charged until lease-aware cleanup confirms removal.
pub async fn delete_avatar(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<UserProfile>, AppError> {
    let mut tx = state.db.begin().await?;
    let old_key: Option<String> = sqlx::query_scalar(
        "SELECT avatar_key FROM users WHERE id = $1 AND deleted_at IS NULL FOR UPDATE",
    )
    .bind(auth.user_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or(AppError::Unauthorized)?;

    let changed = old_key.is_some();
    if changed {
        sqlx::query(
            "UPDATE users SET avatar_key = NULL, avatar_url = NULL, \
                 avatar_version = avatar_version + 1, profile_version = profile_version + 1 \
             WHERE id = $1 AND deleted_at IS NULL",
        )
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
        // The durable reservation remains until lease-aware cleanup succeeds.
    }

    let row = sqlx::query_as::<_, UserProfileRow>(&format!(
        "SELECT {PROFILE_COLUMNS} FROM users WHERE id = $1 AND deleted_at IS NULL"
    ))
    .bind(auth.user_id)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;

    if changed { notify_profile_changed(&state, auth.user_id, row.profile_version).await; }
    Ok(Json(row.into()))
}

/// PROFILE.1: `GET /users/me/avatar` - presigned GET for the current user's
/// uploaded avatar object. 404 when there is no authoritative `avatar_key`.
pub async fn get_avatar(
    State(state): State<AppState>,
    auth: AuthUser,
    axum::extract::Query(query): axum::extract::Query<super::avatar_sizes::AvatarSize>,
) -> Result<Json<AvatarDownloadResponse>, AppError> {
    let key: Option<String> = sqlx::query_scalar(
        "SELECT avatar_key FROM users WHERE id = $1 AND deleted_at IS NULL",
    )
    .bind(auth.user_id)
    .fetch_optional(&state.db)
    .await?
    .flatten();

    let key = key.ok_or_else(|| AppError::NotFound("No avatar set.".into()))?;
    let key = super::avatar_sizes::rendition(&state, &key, auth.user_id, query.size).await?;
    let (download_url, expires_at) = state.s3.presign_get(&key);
    Ok(Json(AvatarDownloadResponse { download_url, expires_at }))
}

/// E.1: `GET /users/{user_id}/avatar` - secure authenticated retrieval of
/// another visible user's avatar. Returns a short-lived presigned GET (the
/// download URL is always derived/temporary transport); never exposes MinIO
/// credentials and never makes the bucket public. 404 is the normal,
/// controlled "no avatar" state (indistinguishable from a missing user).
pub async fn get_user_avatar(
    State(state): State<AppState>,
    auth: AuthUser,
    axum::extract::Path(user_id): axum::extract::Path<Uuid>,
    axum::extract::Query(query): axum::extract::Query<super::avatar_sizes::AvatarSize>,
) -> Result<Json<AvatarDownloadResponse>, AppError> {
    super::blocks::require_visible(&state, &auth, user_id).await?;
    let key: Option<String> = sqlx::query_scalar(
        "SELECT avatar_key FROM users WHERE id = $1 AND deleted_at IS NULL",
    )
    .bind(user_id)
    .fetch_optional(&state.db)
    .await?
    .flatten();

    let key = key.ok_or_else(|| AppError::NotFound("No avatar set.".into()))?;
    let key = super::avatar_sizes::rendition(&state, &key, user_id, query.size).await?;
    let (download_url, expires_at) = state.s3.presign_get(&key);
    Ok(Json(AvatarDownloadResponse { download_url, expires_at }))
}

//! Separate group-call coordination. No group media key enters this module.
pub mod config;
mod discovery;
mod approval;
pub mod livekit;
mod moderation;
mod closure_queue;
pub use closure_queue::ClosureQueue;

use crate::{
    auth::{
        middleware::{lock_active_session, lock_active_user, AuthUser},
        rate_limit,
    },
    error::AppError,
    state::AppState,
};
use axum::{
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    routing::{get, patch, post},
    Json, Router,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use chrono::{DateTime, Duration, Utc};
use config::ConferenceConfig;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

pub const MAX_PARTICIPANTS: i16 = 30;
const ROOM_TTL_SECONDS: i64 = 24 * 60 * 60;
const FIRST_JOIN_TIMEOUT_SECONDS: i64 = 120;
// Embed the same predicate in one SQL statement at admission, at the gateway,
// and when finding stale rooms. A gateway decision uses one database snapshot.
const ROOM_HEALTHY:&str="conference_session_active(c.owner_session_id,c.owner_user_id,c.owner_device_id)
    AND NOT EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=c.id AND NOT conference_session_active(m.session_id,m.user_id,m.device_id))
    AND NOT EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=c.id AND (user_device_blocked(m.device_id,c.owner_user_id) OR user_device_blocked(c.owner_device_id,m.user_id)))
    AND NOT EXISTS(SELECT 1 FROM conference_members a JOIN conference_members b ON a.conference_id=b.conference_id AND a.user_id<b.user_id WHERE a.conference_id=c.id AND (user_device_blocked(a.device_id,b.user_id) OR user_device_blocked(b.device_id,a.user_id)))";

pub fn routes() -> Router<AppState> {
    Router::new()
        .merge(discovery::routes())
        .merge(approval::routes())
        .route("/conferences/capabilities", get(capabilities))
        .route("/conferences/owned", get(owned))
        .route("/conferences/authorize-sfu", get(authorize_sfu_ingress))
        .route("/conferences", post(create))
        .route("/conferences/:id", get(details))
        .route("/conferences/:id/settings", patch(update_settings))
        .route(
            "/conferences/:id/participants/:user/audio",
            post(moderation::set_audio),
        )
        .route(
            "/conferences/:id/participants/:user/remove",
            post(moderation::remove),
        )
        .route("/conferences/:id/join", post(join))
        .route("/conferences/:id/leave", post(leave))
        .route("/conferences/:id/end", post(end))
        .layer(axum::extract::DefaultBodyLimit::max(2048))
}

fn configured() -> Result<ConferenceConfig, AppError> {
    ConferenceConfig::from_env()
        .map_err(|_| AppError::Unavailable("Conference configuration is invalid.".into()))?
        .ok_or_else(|| {
            AppError::Unavailable("Conferences are not configured on this server.".into())
        })
}
fn not_found() -> AppError {
    AppError::NotFound("Conference not found.".into())
}
fn busy() -> AppError {
    AppError::Conflict("busy".into())
}

#[derive(Serialize)]
pub struct Capabilities {
    enabled: bool,
    max_participants: i16,
    e2ee_required: bool,
    host_controls: bool,
    participant_moderation: bool,
    owner_rooms: bool,
    room_directory: bool,
    room_access_v2: bool,
    room_key_handoff: bool,
    room_join_approval: bool,
}
pub async fn capabilities(_auth: AuthUser) -> Json<Capabilities> {
    Json(Capabilities {
        enabled: ConferenceConfig::from_env().ok().flatten().is_some(),
        max_participants: MAX_PARTICIPANTS,
        e2ee_required: true,
        host_controls: true,
        participant_moderation: true,
        owner_rooms: true,
        room_directory: true,
        room_access_v2: true,
        room_key_handoff: true,
        room_join_approval: true,
    })
}
#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CreateRequest {
    pub title: String,
    pub max_participants: i16,
    pub invite_secret_hash: String,
    pub description: Option<String>,
    pub listed: Option<bool>,
    pub access_mode: Option<String>,
    pub password: Option<String>,
    pub approval_required: Option<bool>,
}
#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JoinRequest {
    pub invite_secret: Option<String>,
    pub key_request_id: Option<Uuid>,
    pub password: Option<String>,
    #[serde(default)]
    pub supports_key_handoff: bool,
}
#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UpdateSettingsRequest {
    pub title: Option<String>,
    pub admission_locked: Option<bool>,
    pub expected_revision: Option<i64>,
    pub description: Option<String>,
    pub listed: Option<bool>,
    pub access_mode: Option<String>,
    pub password_action: Option<String>,
    pub password: Option<String>,
    pub approval_required: Option<bool>,
}
#[derive(Serialize, sqlx::FromRow)]
pub struct ParticipantDto {
    pub user_id: Uuid,
    pub device_id: Uuid,
    pub display_name: String,
    pub participant_identity: String,
    pub audio_publish_blocked: bool,
}
#[derive(Serialize, sqlx::FromRow)]
pub struct ConferenceDto {
    pub id: Uuid,
    pub title: String,
    pub owner_user_id: Uuid,
    pub max_participants: i16,
    pub admission_locked: bool,
    pub approval_required: bool,
    pub description: String,
    pub listed: bool,
    pub access_mode: String,
    pub settings_revision: i64,
    pub access_revision: i64,
    pub state: String,
    pub created_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
    pub end_reason: Option<String>,
    #[sqlx(skip)]
    pub participants: Vec<ParticipantDto>,
}
#[derive(Serialize)]
pub struct JoinResponse {
    pub conference: ConferenceDto,
    pub livekit_url: String,
    pub participant_token: String,
    pub participant_identity: String,
    pub token_expires_at: DateTime<Utc>,
    pub e2ee_required: bool,
}
#[derive(sqlx::FromRow)]
struct Room {
    owner_user_id: Uuid,
    owner_device_id: Uuid,
    max_participants: i16,
    admission_locked: bool,
    approval_required: bool,
    invite_secret_hash: Vec<u8>,
    state: String,
    created_at: DateTime<Utc>,
    first_joined_at: Option<DateTime<Utc>>,
    expires_at: DateTime<Utc>,
    listed: bool,
    access_mode: String,
    password_hash: Option<String>,
    settings_revision: i64,
    access_revision: i64,
}

fn invitation_hash(value: &str) -> Result<Vec<u8>, AppError> {
    if value.len() != 43 {
        return Err(not_found());
    }
    let bytes = URL_SAFE_NO_PAD.decode(value).map_err(|_| not_found())?;
    if bytes.len() != 32 || URL_SAFE_NO_PAD.encode(&bytes) != value {
        return Err(not_found());
    }
    Ok(Sha256::digest(bytes).to_vec())
}
fn validate_title(value: &str) -> Result<String, AppError> {
    let title = value.trim();
    if title.is_empty()
        || title.chars().count() > 80
        || title.len() > 320
        || title
            .chars()
            .any(|c| c.is_control() || matches!(c, '\u{2028}' | '\u{2029}'))
    {
        return Err(AppError::Validation(
            "Conference title must contain 1 to 80 characters without control characters.".into(),
        ));
    }
    Ok(title.to_owned())
}
fn validate_settings(request: &UpdateSettingsRequest) -> Result<Option<String>, AppError> {
    if request.title.is_none()
        && request.admission_locked.is_none()
        && request.approval_required.is_none()
        && request.description.is_none()
        && request.listed.is_none()
        && request.access_mode.is_none()
        && request.password_action.is_none()
    {
        return Err(AppError::Validation(
            "Provide title or admission_locked.".into(),
        ));
    }
    request.title.as_deref().map(validate_title).transpose()
}
fn validate_create(request: &CreateRequest) -> Result<(String, Vec<u8>), AppError> {
    let title = validate_title(&request.title)?;
    if !(2..=MAX_PARTICIPANTS).contains(&request.max_participants) {
        return Err(AppError::Validation(
            "max_participants must be between 2 and 30.".into(),
        ));
    }
    if request.invite_secret_hash.len() != 64
        || !request
            .invite_secret_hash
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(AppError::Validation(
            "invite_secret_hash must be a lowercase SHA-256 hex digest.".into(),
        ));
    }
    let hash = hex::decode(&request.invite_secret_hash)
        .map_err(|_| AppError::Validation("Invalid invitation verifier.".into()))?;
    Ok((title, hash))
}
async fn limit(
    state: &AppState,
    auth: &AuthUser,
    operation: &str,
    max: u64,
) -> Result<(), AppError> {
    rate_limit::enforce(
        state,
        &format!("conference:{operation}:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        max,
        60,
    )
    .await
}
async fn lock_room(tx: &mut Transaction<'_, Postgres>, id: Uuid) -> Result<Room, AppError> {
    sqlx::query_as("SELECT owner_user_id,owner_device_id,max_participants,admission_locked,approval_required,invite_secret_hash,state,created_at,first_joined_at,expires_at,description,listed,access_mode,password_hash,settings_revision,access_revision FROM conferences WHERE id=$1 FOR UPDATE").bind(id).fetch_optional(&mut **tx).await?.ok_or_else(not_found)
}
fn first_join_expired(room: &Room, now: DateTime<Utc>) -> bool {
    room.first_joined_at.is_none()
        && room.created_at + Duration::seconds(FIRST_JOIN_TIMEOUT_SECONDS) <= now
}
async fn dto(state: &AppState, id: Uuid) -> Result<ConferenceDto, AppError> {
    let mut room:ConferenceDto=sqlx::query_as("SELECT id,title,owner_user_id,max_participants,admission_locked,approval_required,description,listed,access_mode,settings_revision,access_revision,CASE WHEN state='open' AND expires_at>now() THEN 'active' ELSE 'ended' END AS state,created_at,expires_at,end_reason FROM conferences WHERE id=$1").bind(id).fetch_optional(&state.db).await?.ok_or_else(not_found)?;
    room.participants=sqlx::query_as("SELECT m.user_id,m.device_id,u.display_name,m.user_id::text||':'||m.device_id::text||':'||m.admission_id::text AS participant_identity,COALESCE(p.audio_publish_blocked,false) AS audio_publish_blocked FROM conference_members m JOIN users u ON u.id=m.user_id LEFT JOIN conference_participant_policy p ON p.conference_id=m.conference_id AND p.user_id=m.user_id WHERE m.conference_id=$1 ORDER BY m.joined_at,m.user_id").bind(id).fetch_all(&state.db).await?;
    Ok(room)
}

pub async fn create(
    State(state): State<AppState>,
    auth: AuthUser,
    Json(request): Json<CreateRequest>,
) -> Result<Json<ConferenceDto>, AppError> {
    let config = configured()?;
    limit(&state, &auth, "create", 5).await?;
    let (title, hash) = validate_create(&request)?;
    let description = discovery::description(request.description.as_deref().unwrap_or(""))?;
    let mode = discovery::mode(request.access_mode.as_deref().unwrap_or("invite"))?;
    let password_hash = discovery::create_password(&mode, request.password.as_deref()).await?;
    let id = Uuid::new_v4();
    let mut tx = state.db.begin().await?;
    lock_active_user(&mut tx, &auth).await?;
    let count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM conferences WHERE owner_user_id=$1 AND state<>'ended'",
    )
    .bind(auth.user_id)
    .fetch_one(&mut *tx)
    .await?;
    if count >= 3 {
        return Err(AppError::Conflict(
            "End an existing conference before creating another.".into(),
        ));
    }
    sqlx::query("INSERT INTO conferences(id,owner_user_id,owner_device_id,owner_session_id,title,max_participants,invite_secret_hash,state,expires_at,description,listed,access_mode,password_hash,approval_required) VALUES($1,$2,$3,$4,$5,$6,$7,'provisioning',$8,$9,$10,$11,$12,$13)")
        .bind(id).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id).bind(title).bind(request.max_participants).bind(hash).bind(Utc::now()+Duration::seconds(ROOM_TTL_SECONDS)).bind(description).bind(request.listed.unwrap_or(false)).bind(mode).bind(password_hash).bind(request.approval_required.unwrap_or(false)).execute(&mut *tx).await?;
    tx.commit().await?;
    // The room must exist before any participant JWT is minted. A provisioning
    // tombstone survives a crash and is retired by the reconciler.
    if let Err(error) = livekit::create_room(&config, id, request.max_participants).await {
        sqlx::query("UPDATE conferences SET state='ending',ended_at=now(),end_reason='provisioning_failed' WHERE id=$1").bind(id).execute(&state.db).await?;
        let _ = finish_closure(&state, &config, id).await;
        return Err(error);
    }
    sqlx::query("UPDATE conferences SET state='open' WHERE id=$1 AND state='provisioning'")
        .bind(id)
        .execute(&state.db)
        .await?;
    Ok(Json(dto(&state, id).await?))
}

pub async fn join(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
    Json(request): Json<JoinRequest>,
) -> Result<Json<JoinResponse>, AppError> {
    let config = configured()?;
    limit(&state, &auth, "join", 30).await?;
    if request.invite_secret.is_some() == request.key_request_id.is_some() {
        return Err(AppError::Validation(
            "Provide exactly one conference entry credential.".into(),
        ));
    }
    if request.key_request_id.is_some() && request.password.is_some() {
        return Err(AppError::Validation(
            "Supply the password when creating the key request.".into(),
        ));
    }
    let mut password_proof: Option<(i64, String)> = None;
    loop {
        let mut tx = state.db.begin().await?;
        let room = lock_room(&mut tx, id).await?;
        if room.state != "open"
            || room.expires_at <= Utc::now()
            || first_join_expired(&room, Utc::now())
        {
            return Err(AppError::Gone("Conference has ended.".into()));
        }
        // Same stable account order as private calls and block mutations. Holding
        // the room row also serializes capacity checks and concurrent joins.
        sqlx::query("SELECT id FROM users WHERE id=$2 OR id=$3 OR id IN(SELECT user_id FROM conference_members WHERE conference_id=$1) ORDER BY id FOR UPDATE")
        .bind(id).bind(auth.user_id).bind(room.owner_user_id).execute(&mut *tx).await?;
        lock_active_user(&mut tx, &auth).await?;
        if !room_healthy(&mut tx, id).await? {
            return Err(AppError::Conflict(
                "Conference authorization changed; the room is closing.".into(),
            ));
        }
        let blocked:bool=sqlx::query_scalar("SELECT user_device_blocked($1,$2) OR user_device_blocked($3,$4) OR EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=$5 AND (user_device_blocked($1,m.user_id) OR user_device_blocked(m.device_id,$4)))")
        .bind(auth.device_id).bind(room.owner_user_id).bind(room.owner_device_id).bind(auth.user_id).bind(id).fetch_one(&mut *tx).await?;
        if blocked {
            return Err(not_found());
        }
        let audio_blocked = moderation::admission_audio_policy(&mut tx, id, auth.user_id).await?;
        if let Some(secret) = request.invite_secret.as_deref() {
            let verifier = invitation_hash(secret)?;
            if !crate::auth::refresh_token::constant_time_eq(&verifier, &room.invite_secret_hash) {
                return Err(not_found());
            }
        }
        let current:Option<(Uuid,Uuid,Uuid,Uuid,Option<Uuid>)>=sqlx::query_as("SELECT conference_id,device_id,admission_id,session_id,catalog_request_id FROM conference_members WHERE user_id=$1").bind(auth.user_id).fetch_optional(&mut *tx).await?;
        let mut consume_catalog = false;
        let admission = if let Some((current_room, device, admission, session, catalog_request)) =
            current
        {
            if current_room != id || device != auth.device_id {
                return Err(busy());
            }
            let same_family:bool=sqlx::query_scalar("WITH RECURSIVE family AS(SELECT id,replaced_by FROM sessions WHERE id=$1 UNION SELECT s.id,s.replaced_by FROM sessions s JOIN family f ON s.id=f.replaced_by) SELECT EXISTS(SELECT 1 FROM family WHERE id=$2)")
            .bind(session).bind(auth.session_id).fetch_one(&mut *tx).await?;
            if !same_family {
                return Err(busy());
            }
            if let Some(key_request) = request
                .key_request_id
                .filter(|value| Some(*value) != catalog_request)
            {
                discovery::validate_catalog_join(&mut tx, &auth, &room, id, key_request).await?;
                let updated=sqlx::query("UPDATE conference_members SET catalog_request_id=$2 WHERE user_id=$1 AND admission_id=$3 AND catalog_request_id IS NOT DISTINCT FROM $4")
                    .bind(auth.user_id).bind(key_request).bind(admission).bind(catalog_request).execute(&mut *tx).await?;
                if updated.rows_affected() != 1 {
                    return Err(AppError::SettingsChanged);
                }
                consume_catalog = true;
            }
            admission
        } else {
            if request.invite_secret.is_some() {
                let _ = discovery::mode(&room.access_mode)?;
                if password_proof
                    .as_ref()
                    .is_some_and(|(revision, _)| *revision != room.access_revision)
                {
                    return Err(AppError::SettingsChanged);
                }
                if matches!(room.access_mode.as_str(), "password" | "invite_password") {
                    let hash = room.password_hash.as_ref().ok_or(AppError::Internal)?;
                    if password_proof.as_ref().is_some_and(|(revision, verified)| {
                        *revision != room.access_revision || verified != hash
                    }) {
                        return Err(AppError::SettingsChanged);
                    }
                    if password_proof.is_none() {
                        let (revision, hash) = (room.access_revision, hash.clone());
                        drop(tx);
                        discovery::verify_password(
                            &state,
                            &auth,
                            id,
                            request.password.as_deref(),
                            &hash,
                        )
                        .await?;
                        password_proof = Some((revision, hash));
                        continue;
                    }
                }
            } else if let Some(key_request) = request.key_request_id {
                discovery::validate_catalog_join(&mut tx, &auth, &room, id, key_request).await?;
                consume_catalog = true;
            }
            approval::require(&mut tx, &auth, &room, id).await?;
            if room.admission_locked && room.owner_user_id != auth.user_id {
                return Err(AppError::Conflict("Conference admission is locked.".into()));
            }
            let count: i64 = sqlx::query_scalar(
                "SELECT count(*) FROM conference_members WHERE conference_id=$1",
            )
            .bind(id)
            .fetch_one(&mut *tx)
            .await?;
            if count >= i64::from(room.max_participants) {
                return Err(AppError::Conflict("Conference is full.".into()));
            }
            let admission = Uuid::new_v4();
            let result=sqlx::query("INSERT INTO conference_members(conference_id,user_id,device_id,session_id,admission_id,catalog_request_id) VALUES($1,$2,$3,$4,$5,$6)")
            .bind(id).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id).bind(admission).bind(request.key_request_id).execute(&mut *tx).await;
            match result {
                Ok(_) => {}
                Err(sqlx::Error::Database(error)) if error.code().as_deref() == Some("23505") => {
                    return Err(busy())
                }
                Err(error) => return Err(error.into()),
            }
            approval::consume(&mut tx, &auth, &room, id, admission).await?;
            admission
        };
        if let Some(key_request) = request.key_request_id.filter(|_| consume_catalog) {
            discovery::consume(&mut tx, key_request, admission).await?;
        }
        sqlx::query("UPDATE conference_members SET supports_key_handoff=supports_key_handoff OR $3 WHERE conference_id=$1 AND user_id=$2 AND admission_id=$4")
        .bind(id).bind(auth.user_id).bind(request.supports_key_handoff).bind(admission).execute(&mut *tx).await?;
        // An authenticated retry can arrive after a long transport outage. Renew
        // its handshake grace under the room lock before returning the same
        // admission, so an absence sweep cannot immediately invalidate that grant.
        sqlx::query("UPDATE conference_members SET last_authorized_at=clock_timestamp() WHERE user_id=$1 AND admission_id=$2")
        .bind(auth.user_id).bind(admission).execute(&mut *tx).await?;
        let display_name: String = sqlx::query_scalar(
            "SELECT COALESCE(NULLIF(trim(display_name),''),username) FROM users WHERE id=$1",
        )
        .bind(auth.user_id)
        .fetch_one(&mut *tx)
        .await?;
        let (token, expires) = livekit::participant_token_with_audio_policy(
            &config,
            id,
            auth.user_id,
            auth.device_id,
            admission,
            &display_name,
            audio_blocked,
        )?;
        // The room lock serializes this with timeout reconciliation. The marker
        // commits with successful admission and is never reset by leave/retry.
        sqlx::query(
            "UPDATE conferences SET first_joined_at=COALESCE(first_joined_at,now()) WHERE id=$1",
        )
        .bind(id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        return Ok(Json(JoinResponse {
            conference: dto(&state, id).await?,
            livekit_url: config.url.clone(),
            participant_token: token,
            participant_identity: livekit::participant_identity(
                auth.user_id,
                auth.device_id,
                admission,
            ),
            token_expires_at: expires,
            e2ee_required: true,
        }));
    }
}

pub async fn details(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
) -> Result<Json<ConferenceDto>, AppError> {
    let allowed:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM conferences c WHERE id=$1 AND (c.owner_user_id=$2 OR EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=c.id AND m.user_id=$2 AND m.device_id=$3)) AND NOT user_device_blocked($3,c.owner_user_id) AND NOT EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=c.id AND (user_device_blocked($3,m.user_id) OR user_device_blocked(m.device_id,$2))))").bind(id).bind(auth.user_id).bind(auth.device_id).fetch_one(&state.db).await?;
    if !allowed {
        return Err(not_found());
    }
    Ok(Json(dto(&state, id).await?))
}

/// Account-owned room metadata only: no capability/key reconstruction. Listing
/// lets an owner explicitly end a used-but-empty room without occupying a slot.
pub async fn owned(
    State(state): State<AppState>,
    auth: AuthUser,
) -> Result<Json<Vec<ConferenceDto>>, AppError> {
    let _config = configured()?;
    limit(&state, &auth, "owned", 30).await?;
    let mut tx = state.db.begin().await?;
    lock_active_session(&mut tx, &auth).await?;
    let ids: Vec<Uuid> = sqlx::query_scalar("SELECT c.id FROM conferences c WHERE c.owner_user_id=$1 AND c.state<>'ended' AND NOT EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=c.id AND (user_device_blocked($2,m.user_id) OR user_device_blocked(m.device_id,$1))) ORDER BY c.created_at DESC,c.id LIMIT 3")
        .bind(auth.user_id).bind(auth.device_id).fetch_all(&mut *tx).await?;
    tx.commit().await?;
    let mut rooms = Vec::with_capacity(ids.len());
    for id in ids {
        rooms.push(dto(&state, id).await?);
    }
    Ok(Json(rooms))
}

/// Owner-only metadata/admission policy. The room lock is shared with join/end,
/// so no new admission can pass a committed lock using a stale policy read.
/// Existing admissions and their media keys are deliberately untouched.
pub async fn update_settings(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
    Json(request): Json<UpdateSettingsRequest>,
) -> Result<Json<ConferenceDto>, AppError> {
    discovery::update_settings(state, auth, id, request).await
}
pub async fn leave(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
) -> Result<Json<serde_json::Value>, AppError> {
    let config = configured()?;
    let mut tx = state.db.begin().await?;
    let room = lock_room(&mut tx, id).await?;
    lock_active_session(&mut tx, &auth).await?;
    let member:Option<Uuid>=sqlx::query_scalar("SELECT admission_id FROM conference_members WHERE conference_id=$1 AND user_id=$2 AND device_id=$3").bind(id).bind(auth.user_id).bind(auth.device_id).fetch_optional(&mut *tx).await?;
    if let Some(admission) = member {
        // Keep the busy slot until SFU confirms removal; after commit the gate
        // rejects the old identity even if the client caches a refreshed JWT.
        livekit::remove_participant(&config, id, auth.user_id, auth.device_id, admission).await?;
        sqlx::query(
            "DELETE FROM conference_members WHERE conference_id=$1 AND user_id=$2 AND device_id=$3",
        )
        .bind(id)
        .bind(auth.user_id)
        .bind(auth.device_id)
        .execute(&mut *tx)
        .await?;
        approval::leave(&mut tx, id, auth.user_id, admission).await?;
        sqlx::query("UPDATE conference_key_requests SET status='revoked',consumed_admission_id=NULL,ciphertext=NULL,message_type=NULL,publication_hash=NULL WHERE conference_id=$1 AND requester_user_id=$2 AND consumed_admission_id=$3")
            .bind(id).bind(auth.user_id).bind(admission).execute(&mut *tx).await?;
    } else if room.owner_user_id != auth.user_id {
        return Err(not_found());
    }
    tx.commit().await?;
    Ok(Json(serde_json::json!({"ok":true})))
}
pub async fn end(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
) -> Result<Json<serde_json::Value>, AppError> {
    let config = configured()?;
    let mut tx = state.db.begin().await?;
    let room = lock_room(&mut tx, id).await?;
    if room.owner_user_id != auth.user_id {
        return Err(not_found());
    }
    lock_active_session(&mut tx, &auth).await?;
    sqlx::query("UPDATE conferences SET state='ending',ended_at=COALESCE(ended_at,now()),end_reason=COALESCE(end_reason,'owner_ended') WHERE id=$1 AND state<>'ended'").bind(id).execute(&mut *tx).await?;
    tx.commit().await?;
    if room.state != "ended" {
        finish_closure(&state, &config, id).await?;
    }
    Ok(Json(serde_json::json!({"ok":true})))
}

async fn room_healthy(tx: &mut Transaction<'_, Postgres>, id: Uuid) -> Result<bool, AppError> {
    Ok(sqlx::query_scalar(&format!(
        "SELECT {ROOM_HEALTHY} FROM conferences c WHERE id=$1"
    ))
    .bind(id)
    .fetch_one(&mut **tx)
    .await?)
}

/// Caddy calls this on EVERY public SFU /rtc and /rtc/* handshake. The token
/// comes through an overwritten header; neither URL queries nor raw headers
/// are logged. The SFU itself must be unreachable through an alternate path.
async fn authorize_sfu_ingress(
    State(state): State<AppState>, crate::net::ClientIp(ip): crate::net::ClientIp, headers: HeaderMap,
) -> Result<StatusCode, AppError> {
    rate_limit::enforce(&state,"conference:sfu:ip",ip,600,60).await?;
    authorize_sfu(State(state),headers).await
}
pub async fn authorize_sfu(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<StatusCode, AppError> {
    let _permit=state.resources.sfu_authorization()?;
    let config = configured()?;
    let token = sfu_request_token(&headers)?;
    let (id, identity, audio_restricted) = verified_sfu_grant(&config, token)?;
    let mut principal=identity.split(':');
    let user=principal.next().and_then(|v|Uuid::parse_str(v).ok()).ok_or(AppError::Unauthorized)?;
    let device=principal.next().and_then(|v|Uuid::parse_str(v).ok()).ok_or(AppError::Unauthorized)?;
    // Key by verified identity, not token bytes or rotating admission IDs.
    rate_limit::enforce(&state,&format!("conference:sfu:user:{user}"),std::net::Ipv4Addr::UNSPECIFIED.into(),120,60).await?;
    rate_limit::enforce(&state,&format!("conference:sfu:device:{device}"),std::net::Ipv4Addr::UNSPECIFIED.into(),60,60).await?;
    let allowed:Option<bool>=sqlx::query_scalar(&format!("WITH allowed AS(SELECT member.user_id,member.admission_id FROM conference_members member JOIN conferences c ON c.id=member.conference_id LEFT JOIN conference_participant_policy policy ON policy.conference_id=c.id AND policy.user_id=member.user_id WHERE c.id=$1 AND c.state='open' AND c.expires_at>now() AND member.user_id::text||':'||member.device_id::text||':'||member.admission_id::text=$2 AND NOT COALESCE(policy.removed,false) AND NOT COALESCE(policy.needs_sync,false) AND (NOT COALESCE(policy.audio_publish_blocked,false) OR $3) AND {ROOM_HEALTHY}) UPDATE conference_members member SET last_authorized_at=now() FROM allowed WHERE member.user_id=allowed.user_id AND member.admission_id=allowed.admission_id RETURNING true"))
        .bind(id).bind(identity).bind(audio_restricted).fetch_optional(&state.db).await?;
    if allowed != Some(true) {
        return Err(AppError::Unauthorized);
    }
    Ok(StatusCode::NO_CONTENT)
}
fn single_header<'a>(headers: &'a HeaderMap, name: &str) -> Result<Option<&'a str>, AppError> {
    let values = headers.get_all(name);
    let mut values = values.iter();
    let value = values.next();
    if values.next().is_some() {
        return Err(AppError::Unauthorized);
    }
    value
        .map(|value| value.to_str().map_err(|_| AppError::Unauthorized))
        .transpose()
}
fn sfu_request_token(headers: &HeaderMap) -> Result<&str, AppError> {
    let query = single_header(headers, "x-conference-token")?.filter(|value| !value.is_empty());
    let bearer = single_header(headers, "authorization")?
        .filter(|value| !value.is_empty())
        .map(|value| {
            // Authentication schemes are case insensitive; RFC credentials use
            // one or more SP characters, never comma-combined credentials or HTAB.
            let (scheme, credential) = value.split_once(' ').ok_or(AppError::Unauthorized)?;
            if !scheme.eq_ignore_ascii_case("Bearer") {
                return Err(AppError::Unauthorized);
            }
            let token = credential.trim_start_matches(' ');
            if token.is_empty() {
                return Err(AppError::Unauthorized);
            }
            Ok(token)
        })
        .transpose()?;
    for token in [query, bearer].into_iter().flatten() {
        if token.len() > 8192
            || !token
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
        {
            return Err(AppError::Unauthorized);
        }
    }
    // The SFU may prefer Authorization over the query parameter. The gateway
    // must authorize the exact same credential regardless of that preference.
    if let (Some(query), Some(bearer)) = (query, bearer) {
        if query != bearer {
            return Err(AppError::Unauthorized);
        }
    }
    query.or(bearer).ok_or(AppError::Unauthorized)
}
fn verified_sfu_identity(
    config: &ConferenceConfig,
    token: &str,
) -> Result<(Uuid, String), AppError> {
    verified_sfu_grant(config, token).map(|(room, identity, _)| (room, identity))
}
fn verified_sfu_grant(
    config: &ConferenceConfig,
    token: &str,
) -> Result<(Uuid, String, bool), AppError> {
    let mut validation = jsonwebtoken::Validation::new(jsonwebtoken::Algorithm::HS256);
    validation.set_issuer(&[config.api_key.as_str()]);
    validation.leeway = 5;
    validation.validate_nbf = true;
    let claims = jsonwebtoken::decode::<livekit::Claims>(
        token,
        &jsonwebtoken::DecodingKey::from_secret(config.api_secret.as_bytes()),
        &validation,
    )
    .map_err(|_| AppError::Unauthorized)?
    .claims;
    let room = claims
        .video
        .get("room")
        .and_then(serde_json::Value::as_str)
        .and_then(|name| name.strip_prefix("coco-conference-"))
        .and_then(|id| Uuid::parse_str(id).ok())
        .ok_or(AppError::Unauthorized)?;
    if claims.video.get("roomJoin") != Some(&serde_json::Value::Bool(true)) {
        return Err(AppError::Unauthorized);
    }
    let identity = claims.sub.ok_or(AppError::Unauthorized)?;
    let parts: Vec<_> = identity.split(':').collect();
    if parts.len() != 3 || parts.iter().any(|part| Uuid::parse_str(part).is_err()) {
        return Err(AppError::Unauthorized);
    }
    let audio_restricted = livekit::audio_is_restricted(&claims.video);
    Ok((room, identity, audio_restricted))
}

async fn finish_closure(
    state: &AppState,
    config: &ConferenceConfig,
    id: Uuid,
) -> Result<(), AppError> {
    livekit::delete_room(config, id).await?;
    let mut tx = state.db.begin().await?;
    // New admission already rejects 'ending'. Only confirmed SFU deletion
    // releases busy slots; a failed management call is retried by the sweep.
    finish_database_closure(&mut tx, id).await?;
    tx.commit().await?;
    Ok(())
}

async fn finish_database_closure(
    tx: &mut Transaction<'_, Postgres>,
    id: Uuid,
) -> Result<(), AppError> {
    sqlx::query("DELETE FROM conference_members WHERE conference_id=$1 AND EXISTS(SELECT 1 FROM conferences c WHERE c.id=$1 AND c.state='ending')").bind(id).execute(&mut **tx).await?;
    sqlx::query("UPDATE conferences SET state='ended',sfu_deleted_at=now(),ended_at=COALESCE(ended_at,now()) WHERE id=$1 AND state='ending'").bind(id).execute(&mut **tx).await?;
    sqlx::query("UPDATE conference_key_requests SET status='revoked',consumed_admission_id=NULL,ciphertext=NULL,message_type=NULL,publication_hash=NULL WHERE conference_id=$1 AND status IN ('pending','ready','consumed')").bind(id).execute(&mut **tx).await?;
    approval::close(tx, id).await?;
    Ok(())
}

/// Invoked immediately after security mutations and periodically for expiry.
/// Security closure ends the entire room because former members know the E2EE
/// key. Separate transport moderation does not claim to rotate that shared key.
async fn reconcile_security(state: &AppState, all: bool, users: &[Uuid]) -> Result<(), AppError> {
    let Some(config) = ConferenceConfig::from_env().map_err(|_| AppError::Internal)? else {
        return Ok(());
    };
    let ids:Vec<Uuid>=sqlx::query_scalar(&format!("SELECT id FROM conferences c WHERE state<>'ended' AND ($1 OR c.owner_user_id=ANY($2) OR EXISTS(SELECT 1 FROM conference_members scoped WHERE scoped.conference_id=c.id AND scoped.user_id=ANY($2))) AND (state='ending' OR expires_at<=now() OR (state='provisioning' AND created_at<now()-interval '15 seconds') OR (state='open' AND first_joined_at IS NULL AND created_at<=now()-interval '120 seconds') OR NOT ({ROOM_HEALTHY})) ORDER BY created_at"))
        .bind(all).bind(users).fetch_all(&state.db).await?;
    for id in ids {
        let mut tx = state.db.begin().await?;
        let room = lock_room(&mut tx, id).await?;
        let stale: bool = sqlx::query_scalar(
            "SELECT created_at<now()-interval '15 seconds' FROM conferences WHERE id=$1",
        )
        .bind(id)
        .fetch_one(&mut *tx)
        .await?;
        let reason = if room.expires_at <= Utc::now() {
            Some("expired")
        } else if room.state == "open" && first_join_expired(&room, Utc::now()) {
            Some("provisioning_timeout")
        } else if !room_healthy(&mut tx, id).await? {
            Some("authorization_changed")
        } else if room.state == "provisioning" && stale {
            Some("provisioning_failed")
        } else {
            None
        };
        if let Some(reason) = reason {
            sqlx::query("UPDATE conferences SET state='ending',ended_at=COALESCE(ended_at,now()),end_reason=COALESCE(end_reason,$2) WHERE id=$1 AND state<>'ended'").bind(id).bind(reason).execute(&mut *tx).await?;
        }
        let close = room.state == "ending" || reason.is_some();
        tx.commit().await?;
        if close {
            if finish_closure(state, &config, id).await.is_err() {
                tracing::warn!(conference_id=%id,"conference SFU closure pending; admission denied and busy slots retained");
            }
        }
    }
    Ok(())
}

pub async fn reconcile(state: &AppState) -> Result<(), AppError> {
    let Some(config)=ConferenceConfig::from_env().map_err(|_|AppError::Internal)? else { return Ok(()); };
    after_security_change(state).await;
    moderation::reconcile(state, &config).await?;
    discovery::cleanup(state).await?;
    approval::cleanup(state).await?;
    reap_absent_members(state, &config).await?;
    sqlx::query("DELETE FROM conferences WHERE state='ended' AND ended_at<now()-interval '1 day'")
        .execute(&state.db)
        .await?;
    Ok(())
}
async fn reap_absent_members(state: &AppState, config: &ConferenceConfig) -> Result<(), AppError> {
    // Check occupied rooms even during fresh handshake grace. Otherwise retries
    // could keep renewing a vanished SFU room forever without observing its
    // authoritative not_found response.
    let ids:Vec<Uuid>=sqlx::query_scalar("SELECT DISTINCT c.id FROM conferences c JOIN conference_members m ON m.conference_id=c.id WHERE c.state='open'").fetch_all(&state.db).await?;
    for id in ids {
        let Ok(present) = livekit::list_participants(config, id).await else {
            continue;
        };
        let mut tx = state.db.begin().await?;
        let room = lock_room(&mut tx, id).await?;
        if room.state != "open" {
            continue;
        }
        let Some(present) = present else {
            // A real RoomService not_found confirms the SFU room has gone
            // (for example after its process restarted). Never recreate it:
            // retire its authorization and busy slots atomically instead.
            sqlx::query("UPDATE conferences SET state='ending',ended_at=COALESCE(ended_at,now()),end_reason=COALESCE(end_reason,'media_room_missing') WHERE id=$1")
                .bind(id).execute(&mut *tx).await?;
            finish_database_closure(&mut tx, id).await?;
            tx.commit().await?;
            continue;
        };
        // Recent /rtc authorization renews the grace, including reconnects.
        // A failed SFU query retains the busy slot instead of guessing media
        // presence from the application's websocket or process lifetime.
        // Serialize the absence decision with the gateway's grace renewal.
        // Otherwise a handshake could be authorized after this selection and
        // then have its newly connected SFU participant removed below.
        let absent:Vec<(Uuid,Uuid,Uuid)>=sqlx::query_as("SELECT user_id,device_id,admission_id FROM conference_members WHERE conference_id=$1 AND last_authorized_at<now()-interval '90 seconds' AND NOT(user_id::text||':'||device_id::text||':'||admission_id::text=ANY($2)) FOR UPDATE")
            .bind(id).bind(&present).fetch_all(&mut *tx).await?;
        for (user, device, admission) in absent {
            if livekit::remove_participant(config, id, user, device, admission)
                .await
                .is_err()
            {
                continue;
            }
            sqlx::query("DELETE FROM conference_members WHERE conference_id=$1 AND user_id=$2 AND admission_id=$3 AND last_authorized_at<now()-interval '90 seconds'").bind(id).bind(user).bind(admission).execute(&mut *tx).await?;
        }
        // A successful authoritative SFU observation renews the member lease.
        // An abandoned database busy slot alone cannot keep an account alive.
        sqlx::query("UPDATE conference_members SET last_authorized_at=clock_timestamp() WHERE conference_id=$1 AND user_id::text||':'||device_id::text||':'||admission_id::text=ANY($2)")
            .bind(id).bind(&present).execute(&mut *tx).await?;
        tx.commit().await?;
        // The room lock is released before acquiring users locks. Admission
        // takes users -> room, so reversing that order here would deadlock.
        sqlx::query("UPDATE users u SET auto_delete_last_active_at=clock_timestamp() FROM conference_members m JOIN conferences c ON c.id=m.conference_id WHERE u.id=m.user_id AND m.conference_id=$1 AND m.user_id::text||':'||m.device_id::text||':'||m.admission_id::text=ANY($2) AND c.state='open' AND c.expires_at>clock_timestamp() AND conference_session_active(m.session_id,m.user_id,m.device_id) AND u.auto_delete_last_active_at<=clock_timestamp()-interval '60 seconds'")
            .bind(id).bind(&present).execute(&state.db).await?;
    }
    Ok(())
}
pub async fn after_security_change(state: &AppState) {
    state.resources.conference_closures.request(state,None).await;
}
pub async fn after_user_security_change(state: &AppState, user: Uuid) {
    state.resources.conference_closures.request(state,Some(&[user])).await;
}
pub fn spawn_periodic_sweep(state: AppState) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(5));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            interval.tick().await;
            if reconcile(&state).await.is_err() { tracing::warn!("conference maintenance pending"); }
        }
    });
}

#[cfg(test)]
mod integration_tests;
#[cfg(test)]
mod tests {
    use super::*;
    fn request() -> CreateRequest {
        CreateRequest {
            title: "Test room".into(),
            max_participants: 30,
            invite_secret_hash: hex::encode(Sha256::digest([7u8; 32])),
            ..Default::default()
        }
    }
    #[test]
    fn invitation_uses_independent_32_byte_secret_and_sha256_verifier() {
        let secret = URL_SAFE_NO_PAD.encode([7u8; 32]);
        assert_eq!(
            hex::encode(invitation_hash(&secret).unwrap()),
            request().invite_secret_hash
        );
        for invalid in [
            "short".to_owned(),
            format!("{}=", secret),
            "!".repeat(43),
            URL_SAFE_NO_PAD.encode([7u8; 31]),
        ] {
            assert!(invitation_hash(&invalid).is_err());
        }
    }
    #[test]
    fn validates_limits_title_and_verifier_without_accepting_media_key_fields() {
        assert!(validate_create(&request()).is_ok());
        for capacity in [-1, 0, 1, 31, 100] {
            let mut r = request();
            r.max_participants = capacity;
            assert!(validate_create(&r).is_err());
        }
        for title in [" ".into(), "a".repeat(81), "a\nb".into()] {
            let mut r = request();
            r.title = title;
            assert!(validate_create(&r).is_err());
        }
        let mut r = request();
        r.invite_secret_hash = r.invite_secret_hash.to_uppercase();
        assert!(validate_create(&r).is_err());
        assert!(serde_json::from_value::<CreateRequest>(serde_json::json!({"title":"room","max_participants":2,"invite_secret_hash":"a".repeat(64),"e2ee_key":"never-send-this"})).is_err());
    }
    #[test]
    fn host_settings_are_partial_strict_and_do_not_accept_media_or_participant_controls() {
        for value in [
            serde_json::json!({"title":"  Новое имя  "}),
            serde_json::json!({"admission_locked":true}),
            serde_json::json!({"title":"Room","admission_locked":false}),
        ] {
            let request: UpdateSettingsRequest = serde_json::from_value(value).unwrap();
            assert!(validate_settings(&request).is_ok());
        }
        for value in [
            serde_json::json!({}),
            serde_json::json!({"title":null,"admission_locked":null}),
        ] {
            let request: UpdateSettingsRequest = serde_json::from_value(value).unwrap();
            assert!(validate_settings(&request).is_err());
        }
        for value in [
            serde_json::json!({"title":1}),
            serde_json::json!({"admission_locked":"true"}),
            serde_json::json!({"admission_locked":1}),
            serde_json::json!({"admission_locked":true,"max_participants":31}),
            serde_json::json!({"admission_locked":true,"media_key":"not-permitted"}),
            serde_json::json!({"admission_locked":true,"kick_user":"not-permitted"}),
        ] {
            assert!(serde_json::from_value::<UpdateSettingsRequest>(value).is_err());
        }
    }
    #[test]
    fn host_rename_and_create_share_unicode_title_bounds() {
        assert_eq!(validate_title("  Новое имя  ").unwrap(), "Новое имя");
        assert!(
            validate_title(&"🫶".repeat(80)).is_ok(),
            "count codepoints, not UTF-16 units"
        );
        for title in [
            " ".to_string(),
            "🫶".repeat(81),
            "a\nb".into(),
            "a\u{2028}b".into(),
            "a\u{2029}b".into(),
        ] {
            assert!(validate_title(&title).is_err());
            let mut create = request();
            create.title = title.clone();
            assert!(validate_create(&create).is_err());
            assert!(validate_settings(&UpdateSettingsRequest {
                title: Some(title),
                admission_locked: None,
                ..Default::default()
            })
            .is_err());
        }
    }
    #[test]
    fn signaling_gate_rejects_wrong_secret_and_preserves_admission_generation() {
        let mut config = ConferenceConfig {
            url: "wss://fixture".into(),
            api_url: "http://127.0.0.1:7880".into(),
            api_key: "fixture".into(),
            api_secret: "fixture-secret-at-least-32-bytes-long".into(),
        };
        let (room, user, device, admission) = (
            Uuid::new_v4(),
            Uuid::new_v4(),
            Uuid::new_v4(),
            Uuid::new_v4(),
        );
        let (token, _) =
            livekit::participant_token(&config, room, user, device, admission, "Fixture").unwrap();
        assert_eq!(
            verified_sfu_identity(&config, &token).unwrap(),
            (room, livekit::participant_identity(user, device, admission))
        );
        assert_ne!(
            livekit::participant_identity(user, device, admission),
            livekit::participant_identity(user, device, Uuid::new_v4())
        );
        config.api_secret = "different-fixture-secret-at-least-32-bytes".into();
        assert!(verified_sfu_identity(&config, &token).is_err());
    }
    #[test]
    fn signaling_token_extraction_rejects_ambiguous_or_duplicate_credentials() {
        let mut headers = HeaderMap::new();
        assert!(matches!(
            sfu_request_token(&headers),
            Err(AppError::Unauthorized)
        ));
        headers.insert("x-conference-token", "aaa.bbb.ccc".parse().unwrap());
        assert_eq!(sfu_request_token(&headers).unwrap(), "aaa.bbb.ccc");
        headers.insert("authorization", "bEaReR  aaa.bbb.ccc".parse().unwrap());
        assert_eq!(sfu_request_token(&headers).unwrap(), "aaa.bbb.ccc");
        for invalid in [
            "Bearer ddd.eee.fff",
            "Bearer aaa.bbb.ccc, Bearer aaa.bbb.ccc",
            "Bearer aaa.bbb.ccc ",
            "Bearer\taaa.bbb.ccc",
            "Bearer ",
            "Basic aaa.bbb.ccc",
        ] {
            headers.insert("authorization", invalid.parse().unwrap());
            assert!(
                matches!(sfu_request_token(&headers), Err(AppError::Unauthorized)),
                "invalid or conflicting authorization must not fall back to the query token"
            );
        }
        headers.insert("authorization", "Bearer aaa.bbb.ccc".parse().unwrap());
        headers.append("authorization", "Bearer aaa.bbb.ccc".parse().unwrap());
        assert!(matches!(
            sfu_request_token(&headers),
            Err(AppError::Unauthorized)
        ));
        headers.remove("authorization");
        headers.append("x-conference-token", "aaa.bbb.ccc".parse().unwrap());
        assert!(matches!(
            sfu_request_token(&headers),
            Err(AppError::Unauthorized)
        ));
    }
}

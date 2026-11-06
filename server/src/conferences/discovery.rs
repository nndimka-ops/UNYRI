//! Authenticated directory and an opaque, exact-device Signal key mailbox.
//! No handler accepts a plaintext media key or returns an invitation secret.
use super::*;
use axum::extract::Query;
use base64::engine::general_purpose::STANDARD;
use hmac::{Hmac, Mac};
#[cfg(test)]
use std::sync::{Arc,OnceLock};

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/conferences/directory", get(directory))
        .route(
            "/conferences/:id/key-requests",
            post(request_key).get(provider_poll),
        )
        .route(
            "/conferences/:id/key-requests/:request",
            get(read_key).delete(cancel_key),
        )
        .route(
            "/conferences/:id/key-requests/:request/fulfill",
            post(fulfill).layer(axum::extract::DefaultBodyLimit::max(16 * 1024)),
        )
}

pub(super) fn mode(value: &str) -> Result<String, AppError> {
    if matches!(value, "open" | "password" | "invite" | "invite_password") {
        Ok(value.to_owned())
    } else {
        Err(AppError::Validation(
            "Unknown conference access mode.".into(),
        ))
    }
}
pub(super) fn description(value: &str) -> Result<String, AppError> {
    if value.chars().count() > 240
        || value.len() > 960
        || value
            .chars()
            .any(|c| c.is_control() || matches!(c, '\u{2028}' | '\u{2029}'))
    {
        return Err(AppError::Validation(
            "Conference description is too long or contains control characters.".into(),
        ));
    }
    Ok(value.to_owned())
}
fn validate_password(value: &str) -> Result<(), AppError> {
    if !(6..=128).contains(&value.chars().count())
        || value.len() > 512
        || value
            .chars()
            .any(|c| c.is_control() || matches!(c, '\u{2028}' | '\u{2029}'))
    {
        return Err(AppError::Validation(
            "Conference password must contain 6 to 128 characters without control characters."
                .into(),
        ));
    }
    Ok(())
}
fn password_mode(mode: &str) -> bool {
    matches!(mode, "password" | "invite_password")
}
#[cfg(test)]
fn kdf_gate() -> std::sync::Arc<tokio::sync::Semaphore> {
    crate::auth::password::work_gate()
}
async fn hash_password(password: String) -> Result<String, AppError> {
    crate::auth::password::run(move || {
        crate::auth::password::hash_password(&password)
    })
    .await
}
pub(super) async fn create_password(
    mode: &str,
    password: Option<&str>,
) -> Result<Option<String>, AppError> {
    match (password_mode(mode), password) {
        (true, Some(value)) => {
            validate_password(value)?;
            Ok(Some(hash_password(value.to_owned()).await?))
        }
        (true, None) => Err(AppError::Validation(
            "This conference access mode requires a password.".into(),
        )),
        (false, Some(_)) => Err(AppError::Validation(
            "A password is not allowed for this access mode.".into(),
        )),
        (false, None) => Ok(None),
    }
}
pub(super) async fn verify_password(
    state: &AppState,
    auth: &AuthUser,
    room: Uuid,
    supplied: Option<&str>,
    hash: &str,
) -> Result<(), AppError> {
    let supplied =
        supplied.ok_or_else(|| AppError::Forbidden("Conference password is required.".into()))?;
    if validate_password(supplied).is_err() {
        return Err(AppError::Forbidden(
            "Conference password is incorrect.".into(),
        ));
    }
    limit(state, auth, "password", 12).await?;
    rate_limit::enforce(
        state,
        &format!("conference:password:{}:{room}", auth.device_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        6,
        60,
    )
    .await?;
    let (password, hash) = (supplied.to_owned(), hash.to_owned());
    let valid = crate::auth::password::run(move || {
        Ok(crate::auth::password::verify_password(&password, &hash))
    })
    .await?;
    if valid {
        Ok(())
    } else {
        Err(AppError::Forbidden(
            "Conference password is incorrect.".into(),
        ))
    }
}

pub(super) async fn update_settings(
    state: AppState,
    auth: AuthUser,
    id: Uuid,
    request: UpdateSettingsRequest,
) -> Result<Json<ConferenceDto>, AppError> {
    let _ = configured()?;
    limit(&state, &auth, "settings", 30).await?;
    let title = validate_settings(&request)?;
    let description = request
        .description
        .as_deref()
        .map(description)
        .transpose()?;
    let access_mode = request.access_mode.as_deref().map(mode).transpose()?;
    let extended = request.approval_required.is_some() || description.is_some()
        || request.listed.is_some()
        || access_mode.is_some()
        || request.password_action.is_some()
        || request.password.is_some();
    if extended && request.expected_revision.is_none() {
        return Err(AppError::Validation(
            "expected_revision is required for access settings.".into(),
        ));
    }
    if request.expected_revision.is_some_and(|r| r < 1) {
        return Err(AppError::Validation("Invalid settings revision.".into()));
    }
    let action = request.password_action.as_deref().unwrap_or("keep");
    if !matches!(action, "keep" | "set" | "remove")
        || (action == "set") != request.password.is_some()
    {
        return Err(AppError::Validation(
            "Invalid conference password action.".into(),
        ));
    }
    // Do not spend Argon2 work on a non-owner's request. The row is locked again
    // and its expected revision checked after KDF, never held during KDF.
    let owner: Option<Uuid> =
        sqlx::query_scalar("SELECT owner_user_id FROM conferences WHERE id=$1")
            .bind(id)
            .fetch_optional(&state.db)
            .await?;
    if owner != Some(auth.user_id) {
        return Err(not_found());
    }
    let new_hash = if let Some(password) = request.password.as_deref() {
        validate_password(password)?;
        Some(hash_password(password.to_owned()).await?)
    } else {
        None
    };
    let mut tx = state.db.begin().await?;
    let room = lock_room(&mut tx, id).await?;
    lock_active_user(&mut tx, &auth).await?;
    ensure_open(&room)?;
    if !room_healthy(&mut tx, id).await? {
        return Err(AppError::Conflict(
            "Conference authorization changed; the room is closing.".into(),
        ));
    }
    if request
        .expected_revision
        .is_some_and(|r| r != room.settings_revision)
    {
        return Err(AppError::SettingsChanged);
    }
    let next_mode = access_mode.as_deref().unwrap_or(&room.access_mode);
    let next_hash = if !password_mode(next_mode) {
        None
    } else if action == "set" {
        new_hash
    } else if action == "remove" {
        None
    } else {
        room.password_hash.clone()
    };
    if password_mode(next_mode) && next_hash.is_none() {
        return Err(AppError::Validation(
            "This conference access mode requires a password.".into(),
        ));
    }
    if !password_mode(next_mode) && action == "set" {
        return Err(AppError::Validation(
            "A password is not allowed for this access mode.".into(),
        ));
    }
    let access_changed = request.approval_required.is_some_and(|value| value != room.approval_required)
        || next_mode != room.access_mode
        || next_hash != room.password_hash
        || request
            .admission_locked
            .is_some_and(|lock| lock != room.admission_locked);
    sqlx::query("UPDATE conferences SET title=COALESCE($2,title),admission_locked=COALESCE($3,admission_locked),description=COALESCE($4,description),listed=COALESCE($5,listed),access_mode=$6,password_hash=$7,settings_revision=settings_revision+1,access_revision=access_revision+$8,approval_required=COALESCE($9,approval_required) WHERE id=$1")
        .bind(id).bind(title).bind(request.admission_locked).bind(description).bind(request.listed).bind(next_mode).bind(next_hash).bind(i64::from(access_changed)).bind(request.approval_required).execute(&mut *tx).await?;
    if access_changed {
        approval::settings_changed(&mut tx, id).await?;
        sqlx::query("UPDATE conference_key_requests SET status='revoked',ciphertext=NULL,message_type=NULL,publication_hash=NULL WHERE conference_id=$1 AND status IN ('pending','ready')").bind(id).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    Ok(Json(dto(&state, id).await?))
}

pub(super) fn ensure_open(room: &Room) -> Result<(), AppError> {
    if room.state != "open" || room.expires_at <= Utc::now() || first_join_expired(room, Utc::now())
    {
        Err(AppError::Gone("Conference has ended.".into()))
    } else {
        Ok(())
    }
}
pub(super) async fn pair_allowed(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    id: Uuid,
    owner: Uuid,
    owner_device: Uuid,
) -> Result<(), AppError> {
    let blocked: bool = sqlx::query_scalar("SELECT user_device_blocked($1,$2) OR user_device_blocked($3,$4) OR EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=$5 AND (user_device_blocked($1,m.user_id) OR user_device_blocked(m.device_id,$4)))")
        .bind(auth.device_id).bind(owner).bind(owner_device).bind(auth.user_id).bind(id).fetch_one(&mut **tx).await?;
    if blocked {
        Err(not_found())
    } else {
        Ok(())
    }
}
pub(super) async fn request_room(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    id: Uuid,
) -> Result<Room, AppError> {
    let room = lock_room(tx, id).await?;
    lock_active_user(tx, auth).await?;
    ensure_open(&room)?;
    if !room_healthy(tx, id).await? {
        return Err(AppError::Unavailable(
            "Conference authorization is changing.".into(),
        ));
    }
    pair_allowed(tx, auth, id, room.owner_user_id, room.owner_device_id).await?;
    let _ = moderation::admission_audio_policy(tx, id, auth.user_id).await?;
    Ok(room)
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DirectoryQuery {
    pub q: Option<String>,
    pub cursor: Option<String>,
    pub limit: Option<i64>,
}
#[derive(Serialize, sqlx::FromRow)]
pub struct DirectoryRoom {
    id: Uuid,
    title: String,
    description: String,
    owner_user_id: Uuid,
    owner_display_name: String,
    participant_count: i64,
    max_participants: i16,
    access_mode: String,
    admission_locked: bool,
    approval_required: bool,
    key_available: bool,
    created_at: DateTime<Utc>,
    expires_at: DateTime<Utc>,
}
#[derive(Serialize)]
pub struct DirectoryReply {
    items: Vec<DirectoryRoom>,
    next_cursor: Option<String>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct DirectoryCursor {
    date: DateTime<Utc>,
    id: Uuid,
    fingerprint: String,
}
pub async fn directory(
    State(state): State<AppState>,
    auth: AuthUser,
    Query(query): Query<DirectoryQuery>,
) -> Result<Json<DirectoryReply>, AppError> {
    let _ = configured()?;
    limit(&state, &auth, "directory", 60).await?;
    let search = query.q.unwrap_or_default().trim().to_lowercase();
    if search.chars().count() > 80 || search.len() > 320 || search.chars().any(char::is_control) {
        return Err(AppError::Validation("Invalid room search.".into()));
    }
    let size = query.limit.unwrap_or(20);
    if !(1..=40).contains(&size) {
        return Err(AppError::Validation(
            "Page size must be between 1 and 40.".into(),
        ));
    }
    let fingerprint = hex::encode(Sha256::digest(format!(
        "directory-v1:{}:{}:{search}",
        auth.user_id, auth.device_id
    )));
    let boundary = query
        .cursor
        .as_deref()
        .map(|cursor| {
            if cursor.len() > 512 {
                return Err(AppError::Validation("Invalid page cursor.".into()));
            }
            let bytes = URL_SAFE_NO_PAD
                .decode(cursor)
                .map_err(|_| AppError::Validation("Invalid page cursor.".into()))?;
            let payload: DirectoryCursor = serde_json::from_slice(&bytes)
                .map_err(|_| AppError::Validation("Invalid page cursor.".into()))?;
            if payload.fingerprint != fingerprint || URL_SAFE_NO_PAD.encode(bytes) != cursor {
                return Err(AppError::Validation("Invalid page cursor.".into()));
            }
            Ok::<_, AppError>((payload.date, payload.id))
        })
        .transpose()?;
    let pattern = format!(
        "%{}%",
        search
            .replace('\\', "\\\\")
            .replace('%', "\\%")
            .replace('_', "\\_")
    );
    let sql = format!("SELECT c.id,c.title,c.description,c.owner_user_id,COALESCE(NULLIF(trim(u.display_name),''),u.username) AS owner_display_name,(SELECT count(*) FROM conference_members m WHERE m.conference_id=c.id) AS participant_count,c.max_participants,c.access_mode,c.admission_locked,c.approval_required,c.created_at,c.expires_at,EXISTS(SELECT 1 FROM conference_members p WHERE p.conference_id=c.id AND p.supports_key_handoff AND p.key_service_seen_at>now()-interval '15 seconds' AND conference_session_active(p.session_id,p.user_id,p.device_id) AND NOT EXISTS(SELECT 1 FROM conference_participant_policy q WHERE q.conference_id=p.conference_id AND q.user_id=p.user_id AND (q.removed OR q.needs_sync))) AS key_available FROM conferences c JOIN users u ON u.id=c.owner_user_id WHERE c.listed AND c.state='open' AND c.expires_at>now() AND (c.first_joined_at IS NOT NULL OR c.created_at>now()-interval '120 seconds') AND ({ROOM_HEALTHY}) AND lower(c.title) LIKE $1 ESCAPE '\\' AND NOT user_device_blocked($2,c.owner_user_id) AND NOT user_device_blocked(c.owner_device_id,$3) AND NOT EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=c.id AND (user_device_blocked($2,m.user_id) OR user_device_blocked(m.device_id,$3))) AND NOT EXISTS(SELECT 1 FROM conference_participant_policy p WHERE p.conference_id=c.id AND p.user_id=$3 AND p.removed) AND ($4::timestamptz IS NULL OR (c.created_at,c.id)<($4,$5)) ORDER BY c.created_at DESC,c.id DESC LIMIT $6");
    let mut items: Vec<DirectoryRoom> = sqlx::query_as(&sql)
        .bind(pattern)
        .bind(auth.device_id)
        .bind(auth.user_id)
        .bind(boundary.map(|b| b.0))
        .bind(boundary.map(|b| b.1))
        .bind(size + 1)
        .fetch_all(&state.db)
        .await?;
    let more = items.len() > size as usize;
    items.truncate(size as usize);
    let next_cursor = if more {
        items
            .last()
            .map(|last| {
                serde_json::to_vec(&DirectoryCursor {
                    date: last.created_at,
                    id: last.id,
                    fingerprint: fingerprint.clone(),
                })
                .map(|value| URL_SAFE_NO_PAD.encode(value))
            })
            .transpose()
            .map_err(|_| AppError::Internal)?
    } else {
        None
    };
    Ok(Json(DirectoryReply { items, next_cursor }))
}

#[derive(Clone, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Envelope {
    pub recipient_device_id: Uuid,
    pub message_type: i16,
    pub ciphertext: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct KeyCreate {
    pub request_id: Uuid,
    pub nonce: String,
    pub password: Option<String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Fulfill {
    pub assignment_revision: i64,
    pub access_revision: i64,
    pub envelope: Envelope,
}
#[derive(Serialize)]
pub struct KeyRequest {
    pub id: Uuid,
    pub room_id: Uuid,
    pub requester_user_id: Uuid,
    pub requester_device_id: Uuid,
    pub provider_user_id: Option<Uuid>,
    pub provider_device_id: Option<Uuid>,
    pub provider_identity: Option<String>,
    pub assignment_revision: i64,
    pub nonce: String,
    pub access_revision: i64,
    pub expires_at: DateTime<Utc>,
    pub status: String,
    pub envelope: Option<Envelope>,
}
#[derive(Serialize)]
pub struct KeyList {
    items: Vec<KeyRequest>,
}
#[derive(sqlx::FromRow)]
pub(super) struct KeyRow {
    id: Uuid,
    conference_id: Uuid,
    requester_user_id: Uuid,
    requester_device_id: Uuid,
    requester_session_id: Uuid,
    nonce: Vec<u8>,
    body_fingerprint: Vec<u8>,
    access_revision: i64,
    expires_at: DateTime<Utc>,
    status: String,
    provider_user_id: Option<Uuid>,
    provider_device_id: Option<Uuid>,
    provider_admission_id: Option<Uuid>,
    assignment_revision: i64,
    message_type: Option<i16>,
    ciphertext: Option<Vec<u8>>,
    publication_hash: Option<Vec<u8>>,
    consumed_admission_id: Option<Uuid>,
}
impl KeyRow {
    fn dto(&self) -> KeyRequest {
        KeyRequest {
            id: self.id,
            room_id: self.conference_id,
            requester_user_id: self.requester_user_id,
            requester_device_id: self.requester_device_id,
            provider_user_id: self.provider_user_id,
            provider_device_id: self.provider_device_id,
            provider_identity: self
                .provider_user_id
                .zip(self.provider_device_id)
                .zip(self.provider_admission_id)
                .map(|((u, d), a)| livekit::participant_identity(u, d, a)),
            assignment_revision: self.assignment_revision,
            nonce: URL_SAFE_NO_PAD.encode(&self.nonce),
            access_revision: self.access_revision,
            expires_at: self.expires_at,
            status: self.status.clone(),
            envelope: self
                .ciphertext
                .as_ref()
                .zip(self.message_type)
                .map(|(bytes, kind)| Envelope {
                    recipient_device_id: self.requester_device_id,
                    message_type: kind,
                    ciphertext: STANDARD.encode(bytes),
                }),
        }
    }
}
async fn load_request(
    tx: &mut Transaction<'_, Postgres>,
    room: Uuid,
    id: Uuid,
) -> Result<KeyRow, AppError> {
    sqlx::query_as(
        "SELECT * FROM conference_key_requests WHERE conference_id=$1 AND id=$2 FOR UPDATE",
    )
    .bind(room)
    .bind(id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(not_found)
}
pub(super) async fn same_family(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    original: Uuid,
) -> Result<bool, AppError> {
    sqlx::query_scalar("WITH RECURSIVE family AS(SELECT id,replaced_by FROM sessions WHERE id=$1 AND user_id=$2 AND device_id=$3 UNION SELECT s.id,s.replaced_by FROM sessions s JOIN family f ON s.id=f.replaced_by WHERE s.user_id=$2 AND s.device_id=$3) SELECT EXISTS(SELECT 1 FROM family WHERE id=$4) AND conference_session_active($1,$2,$3)")
        .bind(original).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id).fetch_one(&mut **tx).await.map_err(Into::into)
}
async fn require_requester(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    row: &KeyRow,
) -> Result<(), AppError> {
    if row.requester_user_id != auth.user_id
        || row.requester_device_id != auth.device_id
        || !same_family(tx, auth, row.requester_session_id).await?
    {
        return Err(not_found());
    }
    Ok(())
}
fn nonce(value: &str) -> Result<Vec<u8>, AppError> {
    let bytes = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| AppError::Validation("Invalid key request nonce.".into()))?;
    if bytes.len() != 32 || URL_SAFE_NO_PAD.encode(&bytes) != value || bytes.iter().all(|b| *b == 0)
    {
        return Err(AppError::Validation("Invalid key request nonce.".into()));
    }
    Ok(bytes)
}

fn creation_window(started: std::time::Instant) -> Result<(), AppError> {
    if started.elapsed() >= std::time::Duration::from_secs(60) {
        Err(AppError::Gone(
            "Conference key request creation expired.".into(),
        ))
    } else {
        Ok(())
    }
}

async fn was_cancelled(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    room: Uuid,
    request: Uuid,
) -> Result<bool, AppError> {
    // A token refresh may move forward in the original family. A different
    // account/device or unrelated later login must not poison this operation.
    sqlx::query_scalar("WITH RECURSIVE family AS(SELECT s.id,s.replaced_by FROM conference_key_request_cancellations t JOIN sessions s ON s.id=t.requester_session_id WHERE t.conference_id=$1 AND t.request_id=$2 AND t.requester_user_id=$3 AND t.requester_device_id=$4 AND t.expires_at>clock_timestamp() AND s.user_id=$3 AND s.device_id=$4 UNION SELECT s.id,s.replaced_by FROM sessions s JOIN family f ON s.id=f.replaced_by WHERE s.user_id=$3 AND s.device_id=$4) SELECT EXISTS(SELECT 1 FROM family WHERE id=$5)")
        .bind(room).bind(request).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id).fetch_one(&mut **tx).await.map_err(Into::into)
}

#[cfg(test)]
pub(super) struct KeyCreatePause {
    pub(super) request: Uuid,
    pub(super) entered: tokio::sync::Notify,
    pub(super) resume: tokio::sync::Notify,
}
#[cfg(test)]
fn create_pause() -> &'static std::sync::Mutex<Option<Arc<KeyCreatePause>>> {
    static PAUSE: OnceLock<std::sync::Mutex<Option<Arc<KeyCreatePause>>>> = OnceLock::new();
    PAUSE.get_or_init(|| std::sync::Mutex::new(None))
}
#[cfg(test)]
pub(super) fn set_create_pause(pause: Option<Arc<KeyCreatePause>>) {
    *create_pause().lock().unwrap() = pause;
}
#[cfg(test)]
async fn pause_before_kdf(request: Uuid) {
    let pause = { create_pause().lock().unwrap().clone() };
    if let Some(pause) = pause.filter(|pause| pause.request == request) {
        pause.entered.notify_one();
        pause.resume.notified().await;
    }
}
fn body_fingerprint(
    state: &AppState,
    auth: &AuthUser,
    room: Uuid,
    request: &KeyCreate,
    nonce: &[u8],
) -> Vec<u8> {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(state.config.jwt_secret.as_bytes())
        .expect("HMAC accepts every key length");
    for part in [
        b"COCO-ROOM-REQUEST-v1".as_slice(),
        auth.user_id.as_bytes(),
        auth.device_id.as_bytes(),
        room.as_bytes(),
        request.request_id.as_bytes(),
        nonce,
        &[u8::from(request.password.is_some())],
    ] {
        mac.update(&(part.len() as u64).to_be_bytes());
        mac.update(part);
    }
    mac.finalize().into_bytes().to_vec()
}
async fn status(
    tx: &mut Transaction<'_, Postgres>,
    row: &mut KeyRow,
    value: &str,
) -> Result<(), AppError> {
    sqlx::query("UPDATE conference_key_requests SET status=$2,ciphertext=NULL,message_type=NULL,publication_hash=NULL,consumed_admission_id=NULL WHERE id=$1")
        .bind(row.id).bind(value).execute(&mut **tx).await?;
    row.status = value.to_owned();
    row.ciphertext = None;
    row.message_type = None;
    row.publication_hash = None;
    row.consumed_admission_id = None;
    Ok(())
}
async fn refresh(
    tx: &mut Transaction<'_, Postgres>,
    room: &Room,
    row: &mut KeyRow,
) -> Result<(), AppError> {
    if !matches!(row.status.as_str(), "pending" | "ready") {
        return Ok(());
    }
    if row.expires_at <= Utc::now() {
        return status(tx, row, "expired").await;
    }
    let recipient = AuthUser {
        user_id: row.requester_user_id,
        device_id: row.requester_device_id,
        session_id: row.requester_session_id,
    };
    if row.access_revision != room.access_revision
        || ensure_open(room).is_err()
        || !same_family(tx, &recipient, row.requester_session_id).await?
        || pair_allowed(
            tx,
            &recipient,
            row.conference_id,
            room.owner_user_id,
            room.owner_device_id,
        )
        .await
        .is_err()
        || moderation::admission_audio_policy(tx, row.conference_id, row.requester_user_id)
            .await
            .is_err()
    {
        return status(tx, row, "revoked").await;
    }
    if row.status != "pending" {
        return Ok(());
    }
    let current_valid: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM conference_members m WHERE m.conference_id=$1 AND m.user_id=$2 AND m.device_id=$3 AND m.admission_id=$4 AND m.supports_key_handoff AND m.key_service_seen_at>now()-interval '15 seconds' AND conference_session_active(m.session_id,m.user_id,m.device_id) AND NOT EXISTS(SELECT 1 FROM conference_participant_policy p WHERE p.conference_id=m.conference_id AND p.user_id=m.user_id AND (p.removed OR p.needs_sync)))")
        .bind(row.conference_id).bind(row.provider_user_id).bind(row.provider_device_id).bind(row.provider_admission_id).fetch_one(&mut **tx).await?;
    if current_valid {
        return Ok(());
    }
    let provider: Option<(Uuid, Uuid, Uuid)> = sqlx::query_as("SELECT m.user_id,m.device_id,m.admission_id FROM conference_members m LEFT JOIN conference_participant_policy p ON p.conference_id=m.conference_id AND p.user_id=m.user_id WHERE m.conference_id=$1 AND m.supports_key_handoff AND m.key_service_seen_at>now()-interval '15 seconds' AND conference_session_active(m.session_id,m.user_id,m.device_id) AND NOT COALESCE(p.removed,false) AND NOT COALESCE(p.needs_sync,false) ORDER BY (m.user_id=$2) DESC,m.joined_at,m.user_id LIMIT 1")
        .bind(row.conference_id).bind(room.owner_user_id).fetch_optional(&mut **tx).await?;
    if provider.is_none() && row.provider_user_id.is_none() {
        return Ok(());
    }
    row.assignment_revision = row
        .assignment_revision
        .checked_add(1)
        .ok_or(AppError::Internal)?;
    row.provider_user_id = provider.map(|p| p.0);
    row.provider_device_id = provider.map(|p| p.1);
    row.provider_admission_id = provider.map(|p| p.2);
    sqlx::query("UPDATE conference_key_requests SET provider_user_id=$2,provider_device_id=$3,provider_admission_id=$4,assignment_revision=$5 WHERE id=$1")
        .bind(row.id).bind(row.provider_user_id).bind(row.provider_device_id).bind(row.provider_admission_id).bind(row.assignment_revision).execute(&mut **tx).await?;
    Ok(())
}

pub async fn request_key(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
    Json(request): Json<KeyCreate>,
) -> Result<Json<KeyRequest>, AppError> {
    let started = std::time::Instant::now();
    let _ = configured()?;
    limit(&state, &auth, "key_create", 12).await?;
    rate_limit::enforce(
        &state,
        &format!("conference:key-create:{}:{id}", auth.device_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        4,
        60,
    )
    .await?;
    let nonce = nonce(&request.nonce)?;
    if request.request_id.is_nil() {
        return Err(AppError::Validation("Invalid key request ID.".into()));
    }
    let fingerprint = body_fingerprint(&state, &auth, id, &request, &nonce);
    let mut verified: Option<(i64, String)> = None;
    loop {
        creation_window(started)?;
        let mut tx = state.db.begin().await?;
        let room = request_room(&mut tx, &auth, id).await?;
        creation_window(started)?;
        if was_cancelled(&mut tx, &auth, id, request.request_id).await? {
            return Err(AppError::Gone(
                "Conference key request was cancelled.".into(),
            ));
        }
        let existing: Option<Uuid> =
            sqlx::query_scalar("SELECT id FROM conference_key_requests WHERE id=$1")
                .bind(request.request_id)
                .fetch_optional(&mut *tx)
                .await?;
        if existing.is_some() {
            let mut row = load_request(&mut tx, id, request.request_id).await?;
            require_requester(&mut tx, &auth, &row).await?;
            if !crate::auth::refresh_token::constant_time_eq(&row.body_fingerprint, &fingerprint) {
                return Err(AppError::Conflict("Key request binding changed.".into()));
            }
            refresh(&mut tx, &room, &mut row).await?;
            if password_mode(&room.access_mode)
                && matches!(row.status.as_str(), "pending" | "ready")
            {
                let hash = room.password_hash.as_ref().ok_or(AppError::Internal)?;
                if verified.as_ref().is_some_and(|(revision, saved)| {
                    *revision != room.access_revision || saved != hash
                }) {
                    return Err(AppError::SettingsChanged);
                }
                if verified.is_none() {
                    let (revision, saved) = (room.access_revision, hash.clone());
                    drop(tx);
                    #[cfg(test)]
                    pause_before_kdf(request.request_id).await;
                    verify_password(&state, &auth, id, request.password.as_deref(), &saved).await?;
                    verified = Some((revision, saved));
                    continue;
                }
            }
            tx.commit().await?;
            return Ok(Json(row.dto()));
        }
        if verified
            .as_ref()
            .is_some_and(|(revision, _)| *revision != room.access_revision)
        {
            return Err(AppError::SettingsChanged);
        }
        if !room.listed || !matches!(room.access_mode.as_str(), "open" | "password") {
            return Err(not_found());
        }
        if room.admission_locked {
            return Err(AppError::Conflict("Conference admission is locked.".into()));
        }
        if room.access_mode == "open" && request.password.is_some() {
            return Err(AppError::Validation(
                "A password is not allowed for this access mode.".into(),
            ));
        }
        if password_mode(&room.access_mode) {
            let hash = room.password_hash.as_deref().ok_or(AppError::Internal)?;
            if verified
                .as_ref()
                .is_some_and(|(revision, saved)| *revision != room.access_revision || saved != hash)
            {
                return Err(AppError::SettingsChanged);
            }
            if verified.is_none() {
                let (revision, saved) = (room.access_revision, hash.to_owned());
                drop(tx);
                #[cfg(test)]
                pause_before_kdf(request.request_id).await;
                verify_password(&state, &auth, id, request.password.as_deref(), &saved).await?;
                verified = Some((revision, saved));
                continue;
            }
        }
        approval::require(&mut tx, &auth, &room, id).await?;
        sqlx::query("UPDATE conference_key_requests SET status='expired',ciphertext=NULL,message_type=NULL,publication_hash=NULL WHERE conference_id=$1 AND requester_device_id=$2 AND status IN ('pending','ready') AND expires_at<=now()")
            .bind(id).bind(auth.device_id).execute(&mut *tx).await?;
        let prior: Option<KeyRow> = sqlx::query_as("SELECT * FROM conference_key_requests WHERE conference_id=$1 AND requester_device_id=$2 AND status IN ('pending','ready') FOR UPDATE")
            .bind(id).bind(auth.device_id).fetch_optional(&mut *tx).await?;
        if let Some(mut prior) = prior {
            // A fully authorized new intent replaces only this same device's
            // unfinished same-family handshake, never a consumed admission or
            // an unrelated later login's operation.
            if prior.requester_user_id != auth.user_id
                || !same_family(&mut tx, &auth, prior.requester_session_id).await?
            {
                return Err(AppError::Conflict(
                    "A key request is already pending for this device.".into(),
                ));
            }
            status(&mut tx, &mut prior, "revoked").await?;
        }
        let queued: i64 = sqlx::query_scalar("SELECT count(*) FROM conference_key_requests WHERE conference_id=$1 AND status IN ('pending','ready') AND expires_at>now()")
            .bind(id).fetch_one(&mut *tx).await?;
        if queued >= 64 {
            return Err(AppError::RateLimited);
        }
        creation_window(started)?;
        // The room row remains locked from the post-KDF cancellation check
        // through INSERT, serializing a concurrent DELETE with this commit.
        sqlx::query("INSERT INTO conference_key_requests(id,conference_id,requester_user_id,requester_device_id,requester_session_id,nonce,body_fingerprint,access_revision,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)")
            .bind(request.request_id).bind(id).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id).bind(nonce).bind(fingerprint).bind(room.access_revision).bind(Utc::now()+Duration::seconds(60)).execute(&mut *tx).await?;
        let mut row = load_request(&mut tx, id, request.request_id).await?;
        refresh(&mut tx, &room, &mut row).await?;
        tx.commit().await?;
        return Ok(Json(row.dto()));
    }
}

async fn provider_admission(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    room: Uuid,
) -> Result<Uuid, AppError> {
    let member: Option<(Uuid, Uuid, bool)> = sqlx::query_as("SELECT admission_id,session_id,supports_key_handoff FROM conference_members WHERE conference_id=$1 AND user_id=$2 AND device_id=$3")
        .bind(room).bind(auth.user_id).bind(auth.device_id).fetch_optional(&mut **tx).await?;
    let (admission, session, support) = member.ok_or_else(not_found)?;
    if !support || !same_family(tx, auth, session).await? {
        return Err(not_found());
    }
    Ok(admission)
}
pub async fn provider_poll(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
) -> Result<Json<KeyList>, AppError> {
    let _ = configured()?;
    limit(&state, &auth, "key_provider", 60).await?;
    let mut tx = state.db.begin().await?;
    let room = request_room(&mut tx, &auth, id).await?;
    let admission = provider_admission(&mut tx, &auth, id).await?;
    sqlx::query("UPDATE conference_members SET key_service_seen_at=clock_timestamp() WHERE conference_id=$1 AND user_id=$2 AND admission_id=$3")
        .bind(id).bind(auth.user_id).bind(admission).execute(&mut *tx).await?;
    // Eight assignments per poll, not 64 revalidations multiplied by every
    // room member's heartbeat. Requester read/fulfill independently recheck.
    let ids: Vec<Uuid> = sqlx::query_scalar("SELECT r.id FROM conference_key_requests r WHERE r.conference_id=$1 AND r.status='pending' AND (r.provider_user_id=$2 AND r.provider_device_id=$3 AND r.provider_admission_id=$4 OR NOT EXISTS(SELECT 1 FROM conference_members p WHERE p.conference_id=r.conference_id AND p.user_id=r.provider_user_id AND p.device_id=r.provider_device_id AND p.admission_id=r.provider_admission_id AND p.supports_key_handoff AND p.key_service_seen_at>now()-interval '15 seconds' AND conference_session_active(p.session_id,p.user_id,p.device_id) AND NOT EXISTS(SELECT 1 FROM conference_participant_policy q WHERE q.conference_id=p.conference_id AND q.user_id=p.user_id AND (q.removed OR q.needs_sync)))) ORDER BY r.created_at,r.id LIMIT 8")
        .bind(id).bind(auth.user_id).bind(auth.device_id).bind(admission).fetch_all(&mut *tx).await?;
    let mut items = Vec::new();
    for request in ids {
        let mut row = load_request(&mut tx, id, request).await?;
        refresh(&mut tx, &room, &mut row).await?;
        if row.status == "pending"
            && row.provider_user_id == Some(auth.user_id)
            && row.provider_device_id == Some(auth.device_id)
            && row.provider_admission_id == Some(admission)
            && items.len() < 8
        {
            items.push(row.dto());
        }
    }
    tx.commit().await?;
    Ok(Json(KeyList { items }))
}
pub async fn read_key(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((id, request)): Path<(Uuid, Uuid)>,
) -> Result<Json<KeyRequest>, AppError> {
    let _ = configured()?;
    limit(&state, &auth, "key_read", 90).await?;
    let mut tx = state.db.begin().await?;
    let room = lock_room(&mut tx, id).await?;
    lock_active_user(&mut tx, &auth).await?;
    let mut row = load_request(&mut tx, id, request).await?;
    require_requester(&mut tx, &auth, &row).await?;
    if !room_healthy(&mut tx, id).await? {
        status(&mut tx, &mut row, "revoked").await?;
    } else {
        refresh(&mut tx, &room, &mut row).await?;
    }
    tx.commit().await?;
    Ok(Json(row.dto()))
}
pub async fn cancel_key(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((id, request)): Path<(Uuid, Uuid)>,
) -> Result<Json<serde_json::Value>, AppError> {
    let _ = configured()?;
    limit(&state, &auth, "key_cancel", 30).await?;
    let mut tx = state.db.begin().await?;
    let exists: Option<Uuid> =
        sqlx::query_scalar("SELECT id FROM conferences WHERE id=$1 FOR UPDATE")
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;
    lock_active_user(&mut tx, &auth).await?;
    // Unknown room and absent own request return the same idempotent reply;
    // no hidden-room existence or another user's request data is disclosed.
    if exists.is_none() {
        tx.commit().await?;
        return Ok(Json(serde_json::json!({"ok":true})));
    }
    let row: Option<KeyRow> = sqlx::query_as(
        "SELECT * FROM conference_key_requests WHERE conference_id=$1 AND id=$2 FOR UPDATE",
    )
    .bind(id)
    .bind(request)
    .fetch_optional(&mut *tx)
    .await?;
    if let Some(mut row) = row {
        require_requester(&mut tx, &auth, &row).await?;
        if row.status == "consumed" {
            // Cancellation of a finished handshake is never an implicit Leave.
            tx.commit().await?;
            return Ok(Json(serde_json::json!({"ok":true})));
        }
        if matches!(row.status.as_str(), "pending" | "ready") {
            status(&mut tx, &mut row, "cancelled").await?;
        }
    }
    sqlx::query("INSERT INTO conference_key_request_cancellations(conference_id,request_id,requester_user_id,requester_device_id,requester_session_id,expires_at) VALUES($1,$2,$3,$4,$5,clock_timestamp()+interval '5 minutes') ON CONFLICT(conference_id,request_id,requester_user_id,requester_device_id,requester_session_id) DO UPDATE SET expires_at=GREATEST(conference_key_request_cancellations.expires_at,EXCLUDED.expires_at)")
        .bind(id).bind(request).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(serde_json::json!({"ok":true})))
}
pub async fn fulfill(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((id, request)): Path<(Uuid, Uuid)>,
    Json(body): Json<Fulfill>,
) -> Result<Json<KeyRequest>, AppError> {
    let _ = configured()?;
    limit(&state, &auth, "key_fulfill", 60).await?;
    if body.assignment_revision < 1
        || body.access_revision < 1
        || !matches!(body.envelope.message_type, 1 | 2)
        || body.envelope.ciphertext.len() > 10924
    {
        return Err(AppError::Validation("Invalid key envelope.".into()));
    }
    let ciphertext = STANDARD
        .decode(&body.envelope.ciphertext)
        .map_err(|_| AppError::Validation("Invalid key envelope.".into()))?;
    if ciphertext.is_empty()
        || ciphertext.len() > 8192
        || STANDARD.encode(&ciphertext) != body.envelope.ciphertext
    {
        return Err(AppError::Validation("Invalid key envelope.".into()));
    }
    let mut tx = state.db.begin().await?;
    let room = request_room(&mut tx, &auth, id).await?;
    let admission = provider_admission(&mut tx, &auth, id).await?;
    let mut row = load_request(&mut tx, id, request).await?;
    refresh(&mut tx, &room, &mut row).await?;
    if row.provider_user_id != Some(auth.user_id)
        || row.provider_device_id != Some(auth.device_id)
        || row.provider_admission_id != Some(admission)
    {
        return Err(not_found());
    }
    if row.assignment_revision != body.assignment_revision
        || row.access_revision != body.access_revision
    {
        return Err(AppError::SettingsChanged);
    }
    if body.envelope.recipient_device_id != row.requester_device_id {
        return Err(AppError::Validation(
            "Key envelope recipient does not match the requesting device.".into(),
        ));
    }
    let publication = Sha256::digest(
        [
            &[body.envelope.message_type as u8][..],
            ciphertext.as_slice(),
        ]
        .concat(),
    )
    .to_vec();
    if matches!(row.status.as_str(), "ready" | "consumed")
        && row.publication_hash.as_ref() == Some(&publication)
    {
        tx.commit().await?;
        return Ok(Json(row.dto()));
    }
    if row.status != "pending" {
        return Err(AppError::Conflict(
            "Key request is no longer pending.".into(),
        ));
    }
    let fresh: bool = sqlx::query_scalar("SELECT key_service_seen_at>now()-interval '15 seconds' FROM conference_members WHERE user_id=$1 AND admission_id=$2").bind(auth.user_id).bind(admission).fetch_one(&mut *tx).await?;
    if !fresh {
        return Err(AppError::Conflict("Key provider heartbeat expired.".into()));
    }
    sqlx::query("UPDATE conference_key_requests SET status='ready',message_type=$2,ciphertext=$3,publication_hash=$4 WHERE id=$1")
        .bind(request).bind(body.envelope.message_type).bind(&ciphertext).bind(&publication).execute(&mut *tx).await?;
    row.status = "ready".into();
    row.message_type = Some(body.envelope.message_type);
    row.ciphertext = Some(ciphertext);
    row.publication_hash = Some(publication);
    tx.commit().await?;
    Ok(Json(row.dto()))
}

pub(super) async fn validate_catalog_join(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    room: &Room,
    id: Uuid,
    request: Uuid,
) -> Result<(), AppError> {
    let row = load_request(tx, id, request).await?;
    require_requester(tx, auth, &row).await?;
    if row.expires_at <= Utc::now() || row.status != "ready" {
        return Err(AppError::Gone(
            "Conference key request is no longer valid.".into(),
        ));
    }
    if row.access_revision != room.access_revision {
        return Err(AppError::SettingsChanged);
    }
    if !matches!(room.access_mode.as_str(), "open" | "password") {
        return Err(AppError::Gone(
            "Conference key request is no longer valid.".into(),
        ));
    }
    Ok(())
}
pub(super) async fn consume(
    tx: &mut Transaction<'_, Postgres>,
    request: Uuid,
    admission: Uuid,
) -> Result<(), AppError> {
    sqlx::query("UPDATE conference_key_requests SET status='consumed',consumed_admission_id=$2,ciphertext=NULL,message_type=NULL WHERE id=$1 AND status='ready'").bind(request).bind(admission).execute(&mut **tx).await?;
    Ok(())
}
pub(super) async fn cleanup(state: &AppState) -> Result<(), AppError> {
    sqlx::query("UPDATE conference_key_requests r SET status=CASE WHEN expires_at<=now() THEN 'expired' ELSE 'revoked' END,ciphertext=NULL,message_type=NULL,publication_hash=NULL,consumed_admission_id=NULL WHERE status IN ('pending','ready') AND (expires_at<=now() OR NOT EXISTS(SELECT 1 FROM conferences c WHERE c.id=r.conference_id AND c.state='open' AND c.access_revision=r.access_revision AND c.expires_at>now()) OR NOT conference_session_active(r.requester_session_id,r.requester_user_id,r.requester_device_id))").execute(&state.db).await?;
    sqlx::query("DELETE FROM conference_key_requests WHERE expires_at<now()-interval '5 minutes'")
        .execute(&state.db)
        .await?;
    sqlx::query("DELETE FROM conference_key_request_cancellations WHERE expires_at<=now()")
        .execute(&state.db)
        .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn access_mode_and_unicode_bounds_fail_closed_and_preserve_spaces() {
        for value in ["open", "password", "invite", "invite_password"] {
            assert!(mode(value).is_ok());
        }
        for value in ["", "OPEN", "other"] {
            assert!(mode(value).is_err());
        }
        assert_eq!(description("  room  ").unwrap(), "  room  ");
        assert!(description(&"🦊".repeat(240)).is_ok());
        assert!(description(&"🦊".repeat(241)).is_err());
        assert!(description("hidden\nline").is_err());
        assert!(validate_password(" secret ").is_ok());
        for value in ["short".into(), "a".repeat(129), "a\u{2028}bcdef".into()] {
            assert!(validate_password(&value).is_err());
        }
    }
    #[test]
    fn key_nonce_is_canonical_and_unknown_envelope_fields_are_rejected() {
        let valid = URL_SAFE_NO_PAD.encode([3u8; 32]);
        assert_eq!(nonce(&valid).unwrap(), vec![3u8; 32]);
        for invalid in [
            valid + "=",
            URL_SAFE_NO_PAD.encode([0u8; 32]),
            URL_SAFE_NO_PAD.encode([3u8; 31]),
        ] {
            assert!(nonce(&invalid).is_err());
        }
        assert!(serde_json::from_value::<KeyCreate>(serde_json::json!({"request_id":Uuid::new_v4(),"nonce":URL_SAFE_NO_PAD.encode([1u8;32]),"media_key":"never allowed"})).is_err());
        assert!(serde_json::from_value::<Envelope>(serde_json::json!({"recipient_device_id":Uuid::new_v4(),"message_type":1,"ciphertext":"AA==","invite_secret":"never allowed"})).is_err());
        assert!(creation_window(std::time::Instant::now()).is_ok());
        assert!(
            creation_window(std::time::Instant::now() - std::time::Duration::from_secs(61))
                .is_err(),
            "expired tombstones cannot resurrect an over-budget create handler"
        );
    }
    #[tokio::test]
    async fn password_cpu_and_memory_work_have_a_hard_concurrency_bound() {
        let gate = kdf_gate();
        let first = gate.clone().acquire_owned().await.unwrap();
        let second = gate.acquire_owned().await.unwrap();
        assert!(matches!(
            hash_password("fixture-only-password".into()).await,
            Err(AppError::Unavailable(_))
        ));
        drop(first);
        drop(second);
        let hash = hash_password(" secret ".into()).await.unwrap();
        assert!(crate::auth::password::verify_password(" secret ", &hash));
        assert!(!crate::auth::password::verify_password("secret", &hash));
    }
}

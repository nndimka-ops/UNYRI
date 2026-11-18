//! Creator-controlled admission. Decisions never transport the E2EE room key.
use super::*;

pub(super) const REQUIRED: &str = "Conference creator approval is required.";
const WAIT_SECONDS: i64 = 120;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/conferences/:id/join-requests", post(request).get(pending))
        .route(
            "/conferences/:id/join-requests/:request",
            get(read).delete(cancel),
        )
        .route(
            "/conferences/:id/join-requests/:request/decision",
            post(decide),
        )
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub request_id: Uuid,
    pub invite_secret: Option<String>,
    pub password: Option<String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Decision {
    pub approve: bool,
}
#[derive(Serialize)]
pub struct Entry {
    request_id: Uuid,
    conference_id: Uuid,
    requester_user_id: Uuid,
    requester_device_id: Uuid,
    display_name: String,
    username: String,
    status: String,
    expires_at: DateTime<Utc>,
    retry_at: Option<DateTime<Utc>>,
}
#[derive(Serialize)]
pub struct Queue {
    items: Vec<Entry>,
}
#[derive(sqlx::FromRow)]
struct Row {
    id: Uuid,
    conference_id: Uuid,
    user_id: Uuid,
    device_id: Uuid,
    session_id: Uuid,
    access_revision: i64,
    status: String,
    expires_at: DateTime<Utc>,
}
async fn load(tx: &mut Transaction<'_, Postgres>, room: Uuid, id: Uuid) -> Result<Row, AppError> {
    sqlx::query_as("SELECT id,conference_id,user_id,device_id,session_id,access_revision,status,expires_at FROM conference_join_requests WHERE conference_id=$1 AND id=$2 FOR UPDATE")
        .bind(room).bind(id).fetch_optional(&mut **tx).await?.ok_or_else(not_found)
}
async fn retry_at(
    tx: &mut Transaction<'_, Postgres>,
    room: Uuid,
    user: Uuid,
) -> Result<Option<DateTime<Utc>>, AppError> {
    Ok(sqlx::query_scalar("SELECT blocked_until FROM conference_join_cooldowns WHERE conference_id=$1 AND user_id=$2 AND blocked_until>clock_timestamp()")
        .bind(room).bind(user).fetch_optional(&mut **tx).await?)
}
async fn entry(tx: &mut Transaction<'_, Postgres>, row: &Row) -> Result<Entry, AppError> {
    let (name, username): (String, String) = sqlx::query_as(
        "SELECT COALESCE(NULLIF(trim(display_name),''),username),username FROM users WHERE id=$1",
    )
    .bind(row.user_id)
    .fetch_one(&mut **tx)
    .await?;
    Ok(Entry {
        request_id: row.id,
        conference_id: row.conference_id,
        requester_user_id: row.user_id,
        requester_device_id: row.device_id,
        display_name: name,
        username,
        status: row.status.clone(),
        expires_at: row.expires_at,
        retry_at: retry_at(tx, row.conference_id, row.user_id).await?,
    })
}
async fn require_requester(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    row: &Row,
) -> Result<(), AppError> {
    if row.user_id != auth.user_id
        || row.device_id != auth.device_id
        || !discovery::same_family(tx, auth, row.session_id).await?
    {
        return Err(not_found());
    }
    Ok(())
}
async fn refresh(
    tx: &mut Transaction<'_, Postgres>,
    room: &Room,
    row: &mut Row,
) -> Result<(), AppError> {
    if !matches!(row.status.as_str(), "pending" | "approved" | "consumed") {
        return Ok(());
    }
    let active: bool = sqlx::query_scalar("SELECT conference_session_active($1,$2,$3)")
        .bind(row.session_id)
        .bind(row.user_id)
        .bind(row.device_id)
        .fetch_one(&mut **tx)
        .await?;
    if row.expires_at <= Utc::now() || row.access_revision != room.access_revision || !active {
        row.status = if row.expires_at <= Utc::now() {
            "expired"
        } else {
            "cancelled"
        }
        .into();
        sqlx::query(
            "UPDATE conference_join_requests SET status=$2,consumed_admission_id=NULL WHERE id=$1",
        )
        .bind(row.id)
        .bind(&row.status)
        .execute(&mut **tx)
        .await?;
    }
    Ok(())
}
async fn current_member(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    id: Uuid,
) -> Result<bool, AppError> {
    let session: Option<Uuid> = sqlx::query_scalar("SELECT session_id FROM conference_members WHERE conference_id=$1 AND user_id=$2 AND device_id=$3")
        .bind(id).bind(auth.user_id).bind(auth.device_id).fetch_optional(&mut **tx).await?;
    match session {
        Some(session) => discovery::same_family(tx, auth, session).await,
        None => Ok(false),
    }
}
async fn grant(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    room: &Room,
    id: Uuid,
) -> Result<Option<Uuid>, AppError> {
    if retry_at(tx, id, auth.user_id).await?.is_some() {
        return Ok(None);
    }
    let rows: Vec<(Uuid,Uuid)> = sqlx::query_as("SELECT id,session_id FROM conference_join_requests WHERE conference_id=$1 AND user_id=$2 AND device_id=$3 AND access_revision=$4 AND status IN ('approved','consumed') AND expires_at>clock_timestamp() ORDER BY created_at DESC LIMIT 64")
        .bind(id).bind(auth.user_id).bind(auth.device_id).bind(room.access_revision).fetch_all(&mut **tx).await?;
    for (id, session) in rows {
        if discovery::same_family(tx, auth, session).await? {
            return Ok(Some(id));
        }
    }
    Ok(None)
}
/// Called under the same room lock as password/capacity admission. A current
/// device's recovery is not a new entry and must never prompt its creator again.
pub(super) async fn require(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    room: &Room,
    id: Uuid,
) -> Result<(), AppError> {
    if !room.approval_required
        || room.owner_user_id == auth.user_id
        || current_member(tx, auth, id).await?
    {
        return Ok(());
    }
    if grant(tx, auth, room, id).await?.is_none() {
        return Err(AppError::Conflict(REQUIRED.into()));
    }
    Ok(())
}
pub(super) async fn consume(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    room: &Room,
    id: Uuid,
    admission: Uuid,
) -> Result<(), AppError> {
    if !room.approval_required || room.owner_user_id == auth.user_id {
        return Ok(());
    }
    let request = grant(tx, auth, room, id)
        .await?
        .ok_or_else(|| AppError::Conflict(REQUIRED.into()))?;
    sqlx::query("UPDATE conference_join_requests SET status='consumed',consumed_admission_id=$2,expires_at=$3 WHERE id=$1")
        .bind(request).bind(admission).bind(room.expires_at).execute(&mut **tx).await?;
    Ok(())
}
pub(super) async fn leave(
    tx: &mut Transaction<'_, Postgres>,
    id: Uuid,
    user: Uuid,
    admission: Uuid,
) -> Result<(), AppError> {
    sqlx::query("UPDATE conference_join_requests SET status='cancelled',consumed_admission_id=NULL WHERE conference_id=$1 AND user_id=$2 AND consumed_admission_id=$3")
        .bind(id).bind(user).bind(admission).execute(&mut **tx).await?;
    Ok(())
}
pub(super) async fn close(tx: &mut Transaction<'_, Postgres>, id: Uuid) -> Result<(), AppError> {
    sqlx::query("UPDATE conference_join_requests SET status='cancelled',consumed_admission_id=NULL WHERE conference_id=$1 AND status IN ('pending','approved','consumed')")
        .bind(id).execute(&mut **tx).await?;
    Ok(())
}
pub(super) async fn settings_changed(
    tx: &mut Transaction<'_, Postgres>,
    id: Uuid,
) -> Result<(), AppError> {
    // Existing admitted transports remain authorized; future fresh admissions
    // use the new revision. Pending approvals never survive access changes.
    sqlx::query("UPDATE conference_join_requests SET status='cancelled',consumed_admission_id=NULL WHERE conference_id=$1 AND status IN ('pending','approved')")
        .bind(id).execute(&mut **tx).await?;
    Ok(())
}
async fn owner(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    room: &Room,
    id: Uuid,
) -> Result<(), AppError> {
    if room.owner_user_id != auth.user_id || !current_member(tx, auth, id).await? {
        return Err(not_found());
    }
    Ok(())
}
async fn cancelled(
    tx: &mut Transaction<'_, Postgres>,
    auth: &AuthUser,
    room: Uuid,
    id: Uuid,
) -> Result<bool, AppError> {
    let sessions: Vec<Uuid> = sqlx::query_scalar("SELECT session_id FROM conference_join_cancellations WHERE conference_id=$1 AND request_id=$2 AND user_id=$3 AND device_id=$4 AND expires_at>clock_timestamp()")
        .bind(room).bind(id).bind(auth.user_id).bind(auth.device_id).fetch_all(&mut **tx).await?;
    for session in sessions {
        if discovery::same_family(tx, auth, session).await? {
            return Ok(true);
        }
    }
    Ok(false)
}
fn synthetic(
    auth: &AuthUser,
    id: Uuid,
    request: Uuid,
    status: &str,
    expires: DateTime<Utc>,
) -> Row {
    Row {
        id: request,
        conference_id: id,
        user_id: auth.user_id,
        device_id: auth.device_id,
        session_id: auth.session_id,
        access_revision: 0,
        status: status.into(),
        expires_at: expires,
    }
}

pub async fn request(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
    Json(request): Json<Request>,
) -> Result<Json<Entry>, AppError> {
    let _ = configured()?;
    limit(&state, &auth, "entry_request", 12).await?;
    if request.request_id.is_nil() {
        return Err(AppError::Validation("Invalid join request ID.".into()));
    }
    let mut verified: Option<(i64, String)> = None;
    loop {
        let mut tx = state.db.begin().await?;
        let room = discovery::request_room(&mut tx, &auth, id).await?;
        if cancelled(&mut tx, &auth, id, request.request_id).await? {
            let row = synthetic(&auth, id, request.request_id, "cancelled", Utc::now());
            let dto = entry(&mut tx, &row).await?;
            tx.commit().await?;
            return Ok(Json(dto));
        }
        let existing: bool =
            sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM conference_join_requests WHERE id=$1)")
                .bind(request.request_id)
                .fetch_one(&mut *tx)
                .await?;
        if existing {
            let mut row = load(&mut tx, id, request.request_id).await?;
            require_requester(&mut tx, &auth, &row).await?;
            refresh(&mut tx, &room, &mut row).await?;
            let dto = entry(&mut tx, &row).await?;
            tx.commit().await?;
            return Ok(Json(dto));
        }
        if let Some(until) = retry_at(&mut tx, id, auth.user_id).await? {
            let mut dto = entry(
                &mut tx,
                &synthetic(&auth, id, request.request_id, "cooldown", until),
            )
            .await?;
            dto.retry_at = Some(until);
            tx.commit().await?;
            return Ok(Json(dto));
        }
        if room.owner_user_id == auth.user_id || current_member(&mut tx, &auth, id).await? {
            let dto = entry(
                &mut tx,
                &synthetic(
                    &auth,
                    id,
                    request.request_id,
                    "not_required",
                    room.expires_at,
                ),
            )
            .await?;
            tx.commit().await?;
            return Ok(Json(dto));
        }
        if room.admission_locked {
            return Err(AppError::Conflict("Conference admission is locked.".into()));
        }
        let busy: bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM conference_members WHERE user_id=$1) OR EXISTS(SELECT 1 FROM call_participants_active WHERE user_id=$1)")
            .bind(auth.user_id).fetch_one(&mut *tx).await?;
        if busy {
            return Err(super::busy());
        }
        if let Some(secret) = request.invite_secret.as_deref() {
            if !crate::auth::refresh_token::constant_time_eq(
                &invitation_hash(secret)?,
                &room.invite_secret_hash,
            ) {
                return Err(not_found());
            }
        } else if !room.listed || !matches!(room.access_mode.as_str(), "open" | "password") {
            return Err(not_found());
        }
        if verified
            .as_ref()
            .is_some_and(|(revision, _)| *revision != room.access_revision)
        {
            return Err(AppError::SettingsChanged);
        }
        if matches!(room.access_mode.as_str(), "password" | "invite_password") {
            let hash = room.password_hash.as_ref().ok_or(AppError::Internal)?;
            if verified.as_ref().is_some_and(|(_, saved)| saved != hash) {
                return Err(AppError::SettingsChanged);
            }
            if verified.is_none() {
                let revision = room.access_revision;
                let hash = hash.clone();
                drop(tx);
                discovery::verify_password(&state, &auth, id, request.password.as_deref(), &hash)
                    .await?;
                verified = Some((revision, hash));
                continue;
            }
        } else if request.password.is_some() {
            return Err(AppError::Validation(
                "A password is not allowed for this access mode.".into(),
            ));
        }
        if !room.approval_required {
            let dto = entry(
                &mut tx,
                &synthetic(
                    &auth,
                    id,
                    request.request_id,
                    "not_required",
                    room.expires_at,
                ),
            )
            .await?;
            tx.commit().await?;
            return Ok(Json(dto));
        }
        sqlx::query("UPDATE conference_join_requests SET status='expired' WHERE conference_id=$1 AND status='pending' AND expires_at<=clock_timestamp()")
            .bind(id).execute(&mut *tx).await?;
        let waiting: bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM conference_join_requests WHERE conference_id=$1 AND user_id=$2 AND status='pending')")
            .bind(id).bind(auth.user_id).fetch_one(&mut *tx).await?;
        if waiting {
            return Err(AppError::Conflict(
                "A join request is already waiting for this account.".into(),
            ));
        }
        let count:i64=sqlx::query_scalar("SELECT count(*) FROM conference_join_requests WHERE conference_id=$1 AND status='pending'").bind(id).fetch_one(&mut *tx).await?;
        if count >= 64 {
            return Err(AppError::RateLimited);
        }
        let expires = std::cmp::min(
            room.expires_at,
            Utc::now() + Duration::seconds(WAIT_SECONDS),
        );
        sqlx::query("INSERT INTO conference_join_requests(id,conference_id,user_id,device_id,session_id,access_revision,status,expires_at) VALUES($1,$2,$3,$4,$5,$6,'pending',$7)")
            .bind(request.request_id).bind(id).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id).bind(room.access_revision).bind(expires).execute(&mut *tx).await?;
        let row = load(&mut tx, id, request.request_id).await?;
        let dto = entry(&mut tx, &row).await?;
        tx.commit().await?;
        return Ok(Json(dto));
    }
}
pub async fn read(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((id, request)): Path<(Uuid, Uuid)>,
) -> Result<Json<Entry>, AppError> {
    limit(&state, &auth, "entry_read", 45).await?;
    let mut tx = state.db.begin().await?;
    let room = discovery::request_room(&mut tx, &auth, id).await?;
    let mut row = load(&mut tx, id, request).await?;
    require_requester(&mut tx, &auth, &row).await?;
    refresh(&mut tx, &room, &mut row).await?;
    if !room.approval_required {
        row.status = "not_required".into();
    }
    let dto = entry(&mut tx, &row).await?;
    tx.commit().await?;
    Ok(Json(dto))
}
pub async fn pending(
    State(state): State<AppState>,
    auth: AuthUser,
    Path(id): Path<Uuid>,
) -> Result<Json<Queue>, AppError> {
    limit(&state, &auth, "entry_pending", 30).await?;
    let mut tx = state.db.begin().await?;
    let room = discovery::request_room(&mut tx, &auth, id).await?;
    owner(&mut tx, &auth, &room, id).await?;
    let ids:Vec<Uuid>=sqlx::query_scalar("SELECT id FROM conference_join_requests WHERE conference_id=$1 AND status='pending' AND access_revision=$2 AND expires_at>clock_timestamp() AND conference_session_active(session_id,user_id,device_id) ORDER BY created_at,id LIMIT 64")
        .bind(id).bind(room.access_revision).fetch_all(&mut *tx).await?;
    let mut items = Vec::new();
    for request in ids {
        let mut row = load(&mut tx, id, request).await?;
        refresh(&mut tx, &room, &mut row).await?;
        if row.status == "pending" {
            items.push(entry(&mut tx, &row).await?);
        }
    }
    tx.commit().await?;
    Ok(Json(Queue { items }))
}
pub async fn decide(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((id, request)): Path<(Uuid, Uuid)>,
    Json(decision): Json<Decision>,
) -> Result<Json<Entry>, AppError> {
    limit(&state, &auth, "entry_decision", 60).await?;
    let mut tx = state.db.begin().await?;
    let room = discovery::request_room(&mut tx, &auth, id).await?;
    owner(&mut tx, &auth, &room, id).await?;
    let mut row = load(&mut tx, id, request).await?;
    refresh(&mut tx, &room, &mut row).await?;
    let status = if decision.approve {
        "approved"
    } else {
        "denied"
    };
    if row.status != "pending" {
        if row.status != status && !(decision.approve && row.status == "consumed") {
            return Err(AppError::Conflict(
                "This join request has already been decided or expired.".into(),
            ));
        }
    } else {
        if !room.approval_required {
            return Err(AppError::SettingsChanged);
        }
        let requester = AuthUser {
            user_id: row.user_id,
            device_id: row.device_id,
            session_id: row.session_id,
        };
        discovery::pair_allowed(
            &mut tx,
            &requester,
            id,
            room.owner_user_id,
            room.owner_device_id,
        )
        .await?;
        let _ = moderation::admission_audio_policy(&mut tx, id, row.user_id).await?;
        if decision.approve {
            sqlx::query(
                "DELETE FROM conference_join_cooldowns WHERE conference_id=$1 AND user_id=$2",
            )
            .bind(id)
            .bind(row.user_id)
            .execute(&mut *tx)
            .await?;
        } else {
            sqlx::query("INSERT INTO conference_join_cooldowns(conference_id,user_id,denial_level,blocked_until) VALUES($1,$2,1,clock_timestamp()+interval '1 minute') ON CONFLICT(conference_id,user_id) DO UPDATE SET denial_level=2,blocked_until=clock_timestamp()+interval '10 minutes'")
                .bind(id).bind(row.user_id).execute(&mut *tx).await?;
        }
        row.status = status.into();
        row.expires_at = std::cmp::min(
            room.expires_at,
            Utc::now() + Duration::seconds(WAIT_SECONDS),
        );
        sqlx::query("UPDATE conference_join_requests SET status=$2,decided_at=clock_timestamp(),expires_at=$3 WHERE id=$1")
            .bind(request).bind(status).bind(row.expires_at).execute(&mut *tx).await?;
    }
    let dto = entry(&mut tx, &row).await?;
    tx.commit().await?;
    Ok(Json(dto))
}
pub async fn cancel(
    State(state): State<AppState>,
    auth: AuthUser,
    Path((id, request)): Path<(Uuid, Uuid)>,
) -> Result<Json<serde_json::Value>, AppError> {
    limit(&state, &auth, "entry_cancel", 12).await?;
    if request.is_nil() {
        return Err(AppError::Validation("Invalid join request ID.".into()));
    }
    let mut tx = state.db.begin().await?;
    let _room = discovery::request_room(&mut tx, &auth, id).await?;
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM conference_join_requests WHERE id=$1)")
            .bind(request)
            .fetch_one(&mut *tx)
            .await?;
    if exists {
        let row = load(&mut tx, id, request).await?;
        require_requester(&mut tx, &auth, &row).await?;
        sqlx::query("UPDATE conference_join_requests SET status='cancelled' WHERE id=$1 AND status IN ('pending','approved')").bind(request).execute(&mut *tx).await?;
    }
    sqlx::query("INSERT INTO conference_join_cancellations(conference_id,request_id,user_id,device_id,session_id,expires_at) VALUES($1,$2,$3,$4,$5,clock_timestamp()+interval '5 minutes') ON CONFLICT DO NOTHING")
        .bind(id).bind(request).bind(auth.user_id).bind(auth.device_id).bind(auth.session_id).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(serde_json::json!({"ok":true})))
}
pub(super) async fn cleanup(state: &AppState) -> Result<(), AppError> {
    sqlx::query("UPDATE conference_join_requests SET status='expired',consumed_admission_id=NULL WHERE expires_at<=clock_timestamp() AND status IN ('pending','approved','consumed')").execute(&state.db).await?;
    sqlx::query("DELETE FROM conference_join_requests WHERE expires_at<clock_timestamp()-interval '1 hour' AND status NOT IN ('pending','approved','consumed')").execute(&state.db).await?;
    sqlx::query("DELETE FROM conference_join_cancellations WHERE expires_at<=clock_timestamp()")
        .execute(&state.db)
        .await?;
    sqlx::query("DELETE FROM conference_join_cooldowns p USING conferences c WHERE c.id=p.conference_id AND (c.state='ended' OR c.expires_at<=clock_timestamp())").execute(&state.db).await?;
    Ok(())
}

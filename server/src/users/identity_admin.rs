//! Unlocked owner-only, audited controls shared by native and browser console.
use crate::{
    admin::{audit_event, Admin},
    error::AppError,
    net::ClientIp,
    state::AppState,
};
use axum::{
    extract::{Path, Query, State},
    Json,
};
use serde::Deserialize;
use serde_json::{json, Value};
use uuid::Uuid;

pub fn routes() -> axum::Router<AppState> {
    use axum::routing::{get, post};
    axum::Router::new()
        .route("/identity", get(list))
        .route("/identity/numbers/:number/reserve", post(reserve_number))
        .route("/identity/numbers/:number/assign", post(assign_number))
        .route("/identity/usernames/:name/reserve", post(reserve_username))
        .route("/identity/usernames/:name/free", post(free_username))
}
fn missing() -> AppError {
    AppError::NotFound("Not found.".into())
}
fn conflict() -> AppError {
    AppError::Conflict(
        "Identity allocation is unavailable or changed. Reload and try again.".into(),
    )
}
fn reason(raw: &str) -> Result<&str, AppError> {
    let reason = raw.trim();
    if reason.is_empty() || reason.chars().count() > 2000 {
        return Err(AppError::Validation(
            "A reason of 1-2000 characters is required.".into(),
        ));
    }
    Ok(reason)
}
fn name(raw: &str) -> Result<String, AppError> {
    if !super::identity::valid_token(raw) {
        return Err(AppError::Validation("Invalid reserved username.".into()));
    }
    Ok(raw.to_ascii_lowercase())
}
async fn admit(state: &AppState, admin: &Admin, ip: std::net::IpAddr) -> Result<(), AppError> {
    if admin.role != "owner" {
        return Err(missing());
    }
    super::identity::budget_for_user(
        state,
        admin.user,
        ip,
        "admin",
        state.config.identity.admin,
        60,
    )
    .await
}
// Pin the exact unlocked native authority backing either authentication channel.
async fn authority(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    admin: &Admin,
    target: Option<Uuid>,
) -> Result<(), AppError> {
    // Account locks precede session/role/namespace locks, as in recovery and
    // deletion. Sort both accounts for cross-administrator operations. UUIDs
    // never change: NO KEY UPDATE still serializes writers/deletion, while
    // allowing another account's deletion to insert peer FK tombstones.
    let mut accounts = vec![admin.user];
    if let Some(target) = target {
        accounts.push(target);
    }
    accounts.sort_unstable();
    accounts.dedup();
    for user in accounts {
        let live: Option<Uuid> = sqlx::query_scalar(
            "SELECT id FROM users WHERE id=$1 AND deleted_at IS NULL FOR NO KEY UPDATE",
        )
        .bind(user)
        .fetch_optional(&mut **tx)
        .await?;
        if live.is_none() {
            return Err(missing());
        }
    }
    crate::admin::lock_authority(tx, admin).await
}
#[derive(Deserialize)]
pub struct ListQuery {
    limit: Option<i64>,
    number_after: Option<i64>,
    username_after: Option<String>,
}
pub async fn list(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    admin: Admin,
    Query(query): Query<ListQuery>,
) -> Result<Json<Value>, AppError> {
    admit(&state, &admin, ip).await?;
    let limit = query.limit.unwrap_or(50).clamp(1, 100);
    let mut numbers: Vec<Value>=sqlx::query_scalar("SELECT jsonb_build_object('number',number::text,'user_id',user_id,'canonical',canonical) FROM public_numbers WHERE number>$1 ORDER BY number LIMIT $2")
        .bind(query.number_after.unwrap_or(0)).bind(limit+1).fetch_all(&state.db).await?;
    let mut names: Vec<Value>=sqlx::query_scalar("SELECT jsonb_build_object('name',name,'user_id',user_id,'reserved',reserved,'frozen_until',frozen_until) FROM public_usernames WHERE name>$1 COLLATE \"C\" ORDER BY name LIMIT $2")
        .bind(query.username_after.unwrap_or_default()).bind(limit+1).fetch_all(&state.db).await?;
    let more_numbers = numbers.len() > limit as usize;
    numbers.truncate(limit as usize);
    let more_names = names.len() > limit as usize;
    names.truncate(limit as usize);
    Ok(Json(
        json!({"number_next":if more_numbers { numbers.last().map(|v|v["number"].clone()) } else {None},
        "username_next":if more_names {names.last().map(|v|v["name"].clone())} else {None},"numbers":numbers,"usernames":names}),
    ))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Reason {
    reason: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Assignment {
    user_id: Uuid,
    reason: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Reservation {
    reserved: bool,
    reason: String,
}

pub async fn reserve_number(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    admin: Admin,
    Path(raw): Path<String>,
    Json(req): Json<Reason>,
) -> Result<Json<Value>, AppError> {
    admit(&state, &admin, ip).await?;
    let number = super::identity::parse_number(&raw)
        .ok_or_else(|| AppError::Validation("Invalid public number.".into()))?;
    let reason = reason(&req.reason)?;
    let mut tx = state.db.begin().await?;
    authority(&mut tx, &admin, None).await?;
    let inserted =
        sqlx::query("INSERT INTO public_numbers(number) VALUES($1) ON CONFLICT DO NOTHING")
            .bind(number)
            .execute(&mut *tx)
            .await?
            .rows_affected();
    if inserted == 0 {
        return Err(conflict());
    }
    audit_event(
        &mut tx,
        admin.user,
        "identity:number_reserve",
        None,
        reason,
        json!({"number":raw}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}
pub async fn assign_number(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    admin: Admin,
    Path(raw): Path<String>,
    Json(req): Json<Assignment>,
) -> Result<Json<Value>, AppError> {
    admit(&state, &admin, ip).await?;
    let number = super::identity::parse_number(&raw)
        .ok_or_else(|| AppError::Validation("Invalid public number.".into()))?;
    let reason = reason(&req.reason)?;
    let mut tx = state.db.begin().await?;
    authority(&mut tx, &admin, Some(req.user_id)).await?;
    if !super::identity_backfill::ensure(&mut tx, req.user_id).await? {
        return Err(missing());
    }
    let user: Option<Uuid> = sqlx::query_scalar(
        "SELECT id FROM users WHERE id=$1 AND deleted_at IS NULL FOR NO KEY UPDATE",
    )
    .bind(req.user_id)
    .fetch_optional(&mut *tx)
    .await?;
    if user.is_none() {
        return Err(missing());
    }
    let owner: Option<Option<Uuid>> =
        sqlx::query_scalar("SELECT user_id FROM public_numbers WHERE number=$1 FOR UPDATE")
            .bind(number)
            .fetch_optional(&mut *tx)
            .await?;
    match owner {
        None => return Err(missing()),
        Some(Some(_)) => return Err(conflict()),
        Some(None) => {}
    }
    sqlx::query("UPDATE public_numbers SET user_id=$2 WHERE number=$1")
        .bind(number)
        .bind(req.user_id)
        .execute(&mut *tx)
        .await?;
    audit_event(
        &mut tx,
        admin.user,
        "identity:number_assign",
        Some(req.user_id),
        reason,
        json!({"number":raw}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}
pub async fn reserve_username(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    admin: Admin,
    Path(raw): Path<String>,
    Json(req): Json<Reservation>,
) -> Result<Json<Value>, AppError> {
    admit(&state, &admin, ip).await?;
    let name = name(&raw)?;
    let reason = reason(&req.reason)?;
    let mut tx = state.db.begin().await?;
    authority(&mut tx, &admin, None).await?;
    sqlx::query("INSERT INTO public_usernames(name) VALUES($1) ON CONFLICT DO NOTHING")
        .bind(&name)
        .execute(&mut *tx)
        .await?;
    let owner: Option<Uuid> =
        sqlx::query_scalar("SELECT user_id FROM public_usernames WHERE name=$1 FOR UPDATE")
            .bind(&name)
            .fetch_one(&mut *tx)
            .await?;
    if req.reserved && owner.is_some() {
        return Err(conflict());
    }
    sqlx::query("UPDATE public_usernames SET reserved=$2 WHERE name=$1")
        .bind(&name)
        .bind(req.reserved)
        .execute(&mut *tx)
        .await?;
    audit_event(
        &mut tx,
        admin.user,
        "identity:username_reserve",
        None,
        reason,
        json!({"name":name,"reserved":req.reserved}),
    )
    .await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}
pub async fn free_username(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    admin: Admin,
    Path(raw): Path<String>,
    Json(req): Json<Reason>,
) -> Result<Json<Value>, AppError> {
    admit(&state, &admin, ip).await?;
    let name = name(&raw)?;
    let reason = reason(&req.reason)?;
    let mut tx = state.db.begin().await?;
    let previous: Option<Uuid> =
        sqlx::query_scalar("SELECT user_id FROM public_usernames WHERE name=$1")
            .bind(&name)
            .fetch_optional(&mut *tx)
            .await?
            .ok_or_else(missing)?;
    authority(&mut tx, &admin, previous).await?;
    // Account first, namespace second: same order as ordinary rename/delete.
    if let Some(user) = previous {
        sqlx::query("SELECT id FROM users WHERE id=$1 FOR NO KEY UPDATE")
            .bind(user)
            .execute(&mut *tx)
            .await?;
    }
    let current: Option<Uuid> =
        sqlx::query_scalar("SELECT user_id FROM public_usernames WHERE name=$1 FOR UPDATE")
            .bind(&name)
            .fetch_one(&mut *tx)
            .await?;
    if current != previous {
        return Err(conflict());
    }
    sqlx::query("UPDATE public_usernames SET user_id=NULL,frozen_until=NULL WHERE name=$1")
        .bind(&name)
        .execute(&mut *tx)
        .await?;
    if let Some(user) = current {
        sqlx::query("UPDATE user_identities SET public_username=NULL,username_changed_at=NULL,revision=revision+1 WHERE user_id=$1").bind(user).execute(&mut *tx).await?;
        sqlx::query("UPDATE users SET profile_version=profile_version+1 WHERE id=$1")
            .bind(user)
            .execute(&mut *tx)
            .await?;
    }
    audit_event(
        &mut tx,
        admin.user,
        "identity:username_free",
        current,
        reason,
        json!({"name":name}),
    )
    .await?;
    tx.commit().await?;
    if let Some(user) = current {
        super::identity::notify(&state, user).await;
    }
    Ok(Json(json!({"ok":true})))
}

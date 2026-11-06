use axum::{extract::State, Json};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use uuid::Uuid;
use crate::{admin::Admin, error::AppError, state::AppState};

#[derive(Deserialize, Serialize, sqlx::FromRow)]
pub struct Policy {
    pub name: String, pub operator: String, pub contact: String,
    pub rules: String, pub retention: String,
    pub max_file_bytes: i64, pub user_quota_bytes: i64, pub storage_budget_bytes: i64,
}
impl Policy {
    fn validate(&self) -> Result<(), AppError> {
        if self.name.trim().is_empty() || self.name.len()>200 || self.operator.len()>200 || self.contact.len()>500
            || self.rules.len()>8000 || self.retention.len()>8000
            || !(4097..=crate::attachments::handlers::MAX_ATTACHMENT_ENCRYPTED_SIZE).contains(&self.max_file_bytes)
            || !(0..=1_000_000_000_000_000i64).contains(&self.user_quota_bytes)
            || !(0..=1_000_000_000_000_000i64).contains(&self.storage_budget_bytes) {
            return Err(AppError::Validation("Invalid server policy.".into()));
        }
        Ok(())
    }
}
fn owner(admin: &Admin) -> Result<(), AppError> {
    if admin.role != "owner" { return Err(AppError::NotFound("Not found.".into())); }
    Ok(())
}
async fn policy(state: &AppState) -> Result<Policy, AppError> {
    Ok(sqlx::query_as("SELECT name,operator,contact,rules,retention,max_file_bytes,user_quota_bytes,storage_budget_bytes FROM server_policy WHERE id=true")
        .fetch_one(&state.db).await?)
}
pub async fn public_policy(State(state): State<AppState>) -> Result<Json<Value>, AppError> {
    let p=policy(&state).await?;
    let (minimum_android,minimum_ios,minimum_desktop,registration_open):(String,String,String,bool)=
        sqlx::query_as("SELECT min_android_version,min_ios_version,min_desktop_version,registration_open FROM server_policy WHERE id=true")
            .fetch_one(&state.db).await?;
    let notice:Option<Value>=sqlx::query_scalar("SELECT jsonb_build_object(
        'id',id,'title',title,'body',body,'severity',severity,'created_at',created_at,'expires_at',expires_at)
        FROM server_announcements WHERE disabled_at IS NULL AND expires_at>now()
        ORDER BY created_at DESC LIMIT 1").fetch_optional(&state.db).await?;
    // No administrator identities, statistics, internal addresses or secrets are exposed.
    Ok(Json(json!({"name":p.name,"operator":p.operator,"contact":p.contact,"rules":p.rules,"retention":p.retention,
        "max_file_bytes":p.max_file_bytes,"user_quota_bytes":p.user_quota_bytes,
        "minimum_versions":{"android":minimum_android,"ios":minimum_ios,"desktop":minimum_desktop},
        "registration_open":registration_open,"announcement":notice})))
}
pub async fn admin_policy(State(state): State<AppState>, admin: Admin) -> Result<Json<Policy>,AppError> {
    owner(&admin)?; Ok(Json(policy(&state).await?))
}
pub async fn save_policy(State(state): State<AppState>, admin: Admin, Json(p): Json<Policy>) -> Result<Json<Value>,AppError> {
    owner(&admin)?; p.validate()?;
    let mut tx=state.db.begin().await?;
    sqlx::query("UPDATE server_policy SET name=$1,operator=$2,contact=$3,rules=$4,retention=$5,max_file_bytes=$6,user_quota_bytes=$7,storage_budget_bytes=$8 WHERE id=true")
        .bind(p.name.trim()).bind(p.operator.trim()).bind(p.contact.trim()).bind(p.rules.trim()).bind(p.retention.trim())
        .bind(p.max_file_bytes).bind(p.user_quota_bytes).bind(p.storage_budget_bytes).execute(&mut *tx).await?;
    sqlx::query("INSERT INTO admin_audit(actor,action,reason) VALUES($1,'server:policy','Updated public card and upload limits')").bind(admin.user).execute(&mut *tx).await?;
    tx.commit().await?; Ok(Json(json!({"ok":true})))
}
/// Aggregate attachment storage figures `(used, reserved, budget)`.
///
/// Shared by the storage screen and the dashboard summary so the two surfaces
/// can never report different numbers for the same moment.
pub async fn figures(state: &AppState) -> Result<(i64, i64, i64), AppError> {
    Ok(sqlx::query_as(
        "SELECT (SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations),
                (SELECT COALESCE(SUM(bytes),0)::bigint FROM storage_reservations WHERE pending),
                (SELECT storage_budget_bytes FROM server_policy WHERE id=true)",
    )
    .fetch_one(&state.db)
    .await?)
}

pub async fn storage(State(state): State<AppState>, admin: Admin) -> Result<Json<Value>,AppError> {
    owner(&admin)?;
    let (used,reserved,budget)=figures(&state).await?;
    let users:Vec<Value>=sqlx::query_scalar("SELECT jsonb_build_object('user_id',owner_id,'bytes',SUM(bytes)::bigint) FROM storage_reservations WHERE owner_id IS NOT NULL GROUP BY owner_id ORDER BY SUM(bytes) DESC LIMIT 100").fetch_all(&state.db).await?;
    Ok(Json(json!({"used_bytes":used,"reserved_bytes":reserved,"budget_bytes":budget,
        "warning":budget>0 && used as f64 >= budget as f64 * 0.9,
        "physical_free_bytes":null,"users":users})))
}
pub async fn preview(State(state): State<AppState>, admin: Admin) -> Result<Json<Value>,AppError> {
    owner(&admin)?;
    sqlx::query("DELETE FROM storage_cleanup_plans WHERE expires_at<now() OR session_hash=$1").bind(&admin.token_hash).execute(&state.db).await?;
    let rows:Vec<(Uuid,Uuid,i64,chrono::DateTime<chrono::Utc>)>=sqlx::query_as("SELECT id,uploader_user_id,encrypted_size,created_at FROM attachments WHERE status<>'attached' AND message_id IS NULL AND created_at<now()-interval '24 hours' AND upload_lease_until<now() ORDER BY created_at,id LIMIT 100").fetch_all(&state.db).await?;
    let ids:Vec<Uuid>=rows.iter().map(|r|r.0).collect(); let id=Uuid::new_v4();
    sqlx::query("INSERT INTO storage_cleanup_plans(id,actor,session_hash,expires_at,attachment_ids) VALUES($1,$2,$3,now()+interval '10 minutes',$4)")
        .bind(id).bind(admin.user).bind(&admin.token_hash).bind(&ids).execute(&state.db).await?;
    Ok(Json(json!({"plan_id":id,"bytes":rows.iter().map(|r|r.2).sum::<i64>(),"items":rows.iter().map(|r|json!({"id":r.0,"user_id":r.1,"bytes":r.2,"created_at":r.3})).collect::<Vec<_>>()})))
}
#[derive(Deserialize)] pub struct Cleanup { plan_id:Uuid }
pub async fn cleanup(State(state):State<AppState>,admin:Admin,Json(body):Json<Cleanup>)->Result<Json<Value>,AppError>{
    owner(&admin)?;
    let ids:Option<Vec<Uuid>>=sqlx::query_scalar("SELECT attachment_ids FROM storage_cleanup_plans WHERE id=$1 AND actor=$2 AND session_hash=$3 AND expires_at>now()")
        .bind(body.plan_id).bind(admin.user).bind(&admin.token_hash).fetch_optional(&state.db).await?;
    let ids=ids.ok_or_else(||AppError::Conflict("Preview expired. Request a new preview.".into()))?;
    let mut deleted=0;let mut skipped=0;let mut failed=0;
    for id in ids {
        match crate::attachments::cleanup::delete_abandoned(&state,id,Some(admin.user)).await {
            Ok(true)=>deleted+=1, Ok(false)=>skipped+=1, Err(_)=>failed+=1,
        }
    }
    Ok(Json(json!({"deleted":deleted,"skipped":skipped,"failed":failed})))
}

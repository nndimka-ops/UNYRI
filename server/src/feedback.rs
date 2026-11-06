use axum::{extract::State, Json};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use crate::net::ClientIp;
use crate::{auth::{middleware::AuthUser, rate_limit}, error::AppError, state::AppState};

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Screenshot { mime_type: String, data_base64: String }
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Report {
    id: Uuid,
    description: String,
    platform: String,
    app_version: String,
    os_version: String,
    screenshots: Vec<Screenshot>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    performance: Option<crate::performance::Snapshot>,
}
impl Report {
    fn validate(&self) -> Result<(), AppError> {
        let bad = || AppError::Validation("Invalid bug report or screenshot".into());
        if !(5..=5000).contains(&self.description.trim().chars().count()) ||
            !["ios", "android"].contains(&self.platform.as_str()) ||
            self.app_version.len() > 100 || self.os_version.len() > 100 || self.screenshots.len() > 3 { return Err(bad()); }
        for screenshot in &self.screenshots {
            if screenshot.data_base64.len() > 1_398_104 { return Err(bad()); }
            let bytes = STANDARD.decode(&screenshot.data_base64).map_err(|_| bad())?;
            if bytes.len() > 1024 * 1024 || !match screenshot.mime_type.as_str() {
                "image/jpeg" => bytes.starts_with(&[0xff,0xd8,0xff]),
                "image/png" => bytes.starts_with(b"\x89PNG\r\n\x1a\n"),
                _ => false,
            } { return Err(bad()); }
        }
        if let Some(performance) = &self.performance { performance.validate(600)?; }
        Ok(())
    }
}
#[derive(Serialize)]
pub struct Receipt { id: Uuid }

pub async fn submit(State(state): State<AppState>, ClientIp(addr): ClientIp,
    auth: AuthUser, Json(report): Json<Report>) -> Result<Json<Receipt>, AppError> {
    report.validate()?;
    rate_limit::enforce(&state, "bug-report:ip", addr, 300, 3600).await?;
    rate_limit::enforce(
        &state,
        &format!("bug-report:user:{}", auth.user_id),
        std::net::Ipv4Addr::UNSPECIFIED.into(),
        120,
        3600,
    ).await?;
    let mut tx = state.db.begin().await?;
    // Serialize an owner's retries and limits, including concurrent device requests.
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE").bind(auth.user_id).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM bug_reports WHERE created_at < now() - interval '30 days'").execute(&mut *tx).await?;
    if let Some(owner) = sqlx::query_scalar::<_, Uuid>("SELECT user_id FROM bug_reports WHERE id=$1").bind(report.id).fetch_optional(&mut *tx).await? {
        if owner != auth.user_id { return Err(AppError::Validation("Invalid report ID".into())); }
    } else {
        let count: i64 = sqlx::query_scalar("SELECT count(*) FROM bug_reports WHERE user_id=$1 AND created_at > now() - interval '1 day'").bind(auth.user_id).fetch_one(&mut *tx).await?;
        if count >= 30 { return Err(AppError::RateLimited); }
        let payload = serde_json::to_value(&report).map_err(|_| AppError::Internal)?;
        sqlx::query("INSERT INTO bug_reports (id,user_id,payload) VALUES ($1,$2,$3)")
            .bind(report.id).bind(auth.user_id).bind(payload).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    // Description and screenshots must never be written to general request logs.
    Ok(Json(Receipt { id: report.id }))
}

#[cfg(test)] mod tests {
    use super::*;
    fn valid() -> Report { Report { id: Uuid::new_v4(), description:"Camera is black".into(), platform:"ios".into(), app_version:"1.0".into(), os_version:"18".into(), screenshots:vec![], performance:None } }
    #[test] fn rejects_invalid_inputs() {
        assert!(valid().validate().is_ok());
        let mut r=valid(); r.description="   ".into(); assert!(r.validate().is_err());
        r=valid(); r.screenshots.push(Screenshot {mime_type:"image/jpeg".into(),data_base64: STANDARD.encode(b"<script>bad</script>")}); assert!(r.validate().is_err());
        r=valid(); r.screenshots=(0..4).map(|_|Screenshot{mime_type:"image/png".into(),data_base64:STANDARD.encode(b"\x89PNG\r\n\x1a\n")}).collect(); assert!(r.validate().is_err());
        r=valid(); r.screenshots.push(Screenshot{mime_type:"image/jpeg".into(),data_base64:"a".repeat(1_398_105)}); assert!(r.validate().is_err());
        r=valid();r.screenshots.push(Screenshot{mime_type:"image/png".into(),data_base64:STANDARD.encode(b"\x89PNG\r\n\x1a\n")}); assert!(r.validate().is_ok());
    }
    #[test] fn rejects_extra_fields() { assert!(serde_json::from_value::<Report>(serde_json::json!({"id":Uuid::new_v4(),"description":"An error","platform":"ios","app_version":"1","os_version":"18","screenshots":[],"token":"secret"})).is_err()); }
    #[test] fn optional_performance_is_strict_and_legacy_reports_still_work() {
        let mut v=serde_json::to_value(valid()).unwrap();
        assert!(v.get("performance").is_none());
        assert!(serde_json::from_value::<Report>(v.clone()).unwrap().validate().is_ok());
        v["performance"]=serde_json::json!({"schema_version":1,"recording_id":Uuid::new_v4(),"platform":"ios","app_version":"1","build":"25","os_version":"26.0","hardware":"iPhone16,2","dropped":0,"events":[{"sequence":1,"at_ms":1,"kind":"touch","screen":"chat","metrics":{"duration_ms":1}}]});
        assert!(serde_json::from_value::<Report>(v.clone()).unwrap().validate().is_ok());
        v["performance"]["events"][0]["metrics"]["private_text"]=serde_json::json!(1);
        assert!(serde_json::from_value::<Report>(v).unwrap().validate().is_err());
    }
}

pub fn spawn_cleanup(state: AppState) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(3600));
        loop {
            interval.tick().await;
            if sqlx::query("DELETE FROM bug_reports WHERE created_at < now() - interval '30 days'").execute(&state.db).await.is_err() {
                tracing::warn!("Bug report retention cleanup failed");
            }
        }
    });
}

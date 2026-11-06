use axum::extract::State;
use axum::Json;
use serde::Serialize;

use crate::state::AppState;

#[derive(Serialize)]
pub struct HealthResponse {
    status: &'static str,
    database: bool,
    redis: bool,
}

/// Reports process liveness plus reachability of PostgreSQL and Redis.
pub async fn health_handler(State(state): State<AppState>) -> Json<HealthResponse> {
    let database = sqlx::query("SELECT 1").execute(&state.db).await.is_ok();

    let mut redis_conn = state.redis.clone();
    let redis: bool = redis::cmd("PING")
        .query_async::<_, String>(&mut redis_conn)
        .await
        .is_ok();

    let status = if database && redis { "ok" } else { "degraded" };

    Json(HealthResponse {
        status,
        database,
        redis,
    })
}

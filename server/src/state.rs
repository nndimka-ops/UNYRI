use std::sync::Arc;

use redis::aio::ConnectionManager;
use sqlx::PgPool;

use crate::config::Config;
use crate::push::PushService;
use crate::storage::S3Client;
use crate::websocket::registry::ConnectionRegistry;

/// Shared application state, cloned cheaply into every request/connection handler.
#[derive(Clone)]
pub struct AppState {
    pub resources: Arc<crate::resources::RuntimeResources>,
    pub db: PgPool,
    pub redis: ConnectionManager,
    pub config: Config,
    pub ws_registry: Arc<ConnectionRegistry>,
    pub s3: Arc<S3Client>,
    pub push: Arc<PushService>,
}

use sqlx::postgres::PgPoolOptions;
use tokio::signal;
use tracing_subscriber::EnvFilter;

use messenger_server::config::Config;
use messenger_server::state::AppState;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();
    // A local management request is writable only by principals that can already
    // modify the server binary directory. No remote shutdown endpoint is exposed.
    let stop_file = std::env::current_exe()?.with_file_name(format!("shutdown-{}.request", std::process::id()));
    match std::fs::remove_file(&stop_file) {
        Ok(()) => {},
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {},
        Err(e) => return Err(e.into()),
    }


    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .init();

    let config = Config::from_env()?;

    let db = PgPoolOptions::new()
        .max_connections(config.database_max_connections)
        .connect(&config.database_url)
        .await?;

    sqlx::migrate!().run(&db).await?;
    tracing::info!("database migrations applied");

    let redis_client = redis::Client::open(config.redis_url.clone())?;
    let redis = redis_client.get_connection_manager().await?;

    // Pre-warm the timing-safe dummy hash used for nonexistent-user logins.
    messenger_server::auth::password::dummy_hash();

    let s3 = std::sync::Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    if let Err(err) = s3.ensure_bucket().await {
        tracing::warn!(error = %err, "could not ensure the attachments bucket exists");
    }
    // A transient S3 outage must not require restarting the chat server. Until
    // verification succeeds, no delegated writes are issued. Rechecking also
    // fails closed if an operator enables unsupported bucket versioning.
    let storage_health=s3.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(60)).await;
            if storage_health.ensure_bucket().await.is_err() {
                tracing::warn!("storage upload verification unavailable");
            }
        }
    });
    let push = messenger_server::push::build_from_config(&config);
    let state = AppState { resources: Default::default(),
        db,
        redis,
        config: config.clone(),
        ws_registry: Default::default(),
        s3,
        push: push.clone(),
    };
    if messenger_server::users::deletion::reconcile_legacy_storage(&state).await.is_err() {
        tracing::warn!("previous storage uploads await revocation and quota reconciliation");
    }

    messenger_server::attachments::cleanup::spawn_periodic_cleanup(state.clone());
    messenger_server::history_backups::media::spawn_cleanup(state.clone());
    messenger_server::history_transfer::spawn_cleanup(state.clone());
    messenger_server::direct_transfers::spawn_cleanup(state.clone());
    messenger_server::feedback::spawn_cleanup(state.clone());
    messenger_server::users::deletion::spawn_cleanup(state.clone());
    messenger_server::users::auto_delete::spawn_worker(state.clone());
    messenger_server::presence::spawn_persistence(state.clone());
    messenger_server::calls::handlers::spawn_periodic_sweep(state.clone());
    messenger_server::conferences::spawn_periodic_sweep(state.clone());
    messenger_server::push::spawn_worker_if_enabled(push, state.clone());

    let addr = format!("{}:{}", config.host, config.port);
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!("listening on {addr}");

    axum::serve(
        listener,
        messenger_server::app(state).into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal(stop_file))
    .await?;

    Ok(())
}

async fn shutdown_signal(stop_file: std::path::PathBuf) {
    let ctrl_c = async {
        if let Err(error) = signal::ctrl_c().await {
            tracing::error!(%error, "Ctrl+C handler unavailable; shutting down safely");
        }
    };

    #[cfg(unix)]
    let terminate = async {
        match signal::unix::signal(signal::unix::SignalKind::terminate()) {
            Ok(mut handler) => { handler.recv().await; }
            Err(error) => tracing::error!(%error, "SIGTERM handler unavailable; shutting down safely"),
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    let local_stop = async {
        loop {
            if stop_file.is_file() {
                let _ = std::fs::remove_file(&stop_file);
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(200)).await;
        }
    };
    tokio::select! {
        _ = local_stop => {},
        _ = ctrl_c => {},
        _ = terminate => {},
    }

    tracing::info!("shutdown requested, draining connections");
}

#[cfg(test)]
mod tests {
    #[tokio::test]
    async fn local_shutdown_request_wakes_graceful_drain() {
        let request = std::env::temp_dir().join(format!("coco-stop-test-{}", uuid::Uuid::new_v4()));
        let waiting = tokio::spawn(super::shutdown_signal(request.clone()));
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        assert!(!waiting.is_finished(), "No request must leave the server running");
        std::fs::write(&request, "stop").unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(2), waiting)
            .await.unwrap().unwrap();
        assert!(!request.exists(), "Handled requests must be removed");
    }
}

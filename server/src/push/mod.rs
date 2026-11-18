pub mod fcm;
pub mod apns;
pub mod delivery;
pub mod handlers;
pub mod models;
pub mod outbox;

pub use models::{Presentation, PushError, PushEvent, PushPriority, PushProvider, PushSendOutcome};
pub use outbox::PushService;

use crate::config::Config;
use crate::state::AppState;
use std::sync::Arc;

/// Builds the app-scoped push dispatcher from configuration. Returns a
/// disabled no-op service when FCM credentials are absent - the server still
/// boots and serves everything else (local dev/CI stay Firebase-free).
pub fn build_from_config(config: &Config) -> Arc<PushService> {
    let offline = ["UNYRI_OFFLINE", "COCO_OFFLINE"].iter().any(|name|
        std::env::var(name).is_ok_and(|v| v == "1" || v.eq_ignore_ascii_case("true")));
    if offline { return Arc::new(PushService::disabled()); }
    let concurrency = alert_concurrency(
        std::env::var("PUSH_APNS_CONCURRENCY").ok().as_deref(),
        config.database_max_connections,
    );
    Arc::new(
        build_fcm(config)
            .with_apns(apns::configured_providers())
            .with_alert_concurrency(concurrency),
    )
}

/// Parallel APNs alert sends: PUSH_APNS_CONCURRENCY (default 4, at most 16).
/// The worker runs this many alert sends plus one send on each other lane
/// (voip, FCM calls, FCM bulk, APNs background), each holding at most one
/// pooled connection at a time, so the limit stays below the pool size and
/// request handlers always keep connections.
fn alert_concurrency(requested: Option<&str>, database_max_connections: u32) -> usize {
    let requested = requested
        .and_then(|value| value.trim().parse::<usize>().ok())
        .unwrap_or(outbox::DEFAULT_ALERT_CONCURRENCY);
    let ceiling = (database_max_connections as usize).saturating_sub(outbox::LANES).max(1);
    requested.clamp(1, 16).min(ceiling)
}

fn build_fcm(config: &Config) -> PushService {
    let Some(project_id) = config.fcm.project_id.clone() else {
        return PushService::disabled();
    };
    let Some(credentials_path) = config.fcm.service_account_file.clone() else {
        tracing::warn!("FCM_PROJECT_ID set but no service-account credential file - FCM disabled");
        return PushService::disabled();
    };

    // Bounded HTTP client so an unreachable FCM endpoint (e.g. no Internet
    // uplink) fails fast instead of hanging an outbox sweep - the worker
    // then backs off and retries later; core endpoints are unaffected.
    let transport = Arc::new(fcm::ReqwestFcmTransport::new(
        reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_secs(10))
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .expect("reqwest client builder cannot fail with these options"),
    ));
    let token_source = match fcm::ServiceAccountTokenSource::from_file(
        std::path::Path::new(&credentials_path),
        transport.clone(),
    ) {
        Ok(source) => source,
        Err(err) => {
            tracing::error!(%err, "failed to load FCM service account - FCM disabled");
            return PushService::disabled();
        }
    };
    let token_provider = fcm::FcmAccessTokenProvider::new(Arc::new(token_source));
    let provider = fcm::FcmPushProvider::new(project_id, token_provider, transport);
    PushService::new(Arc::new(provider))
}

/// Convenience for tests: inject a fake provider directly.
pub fn build_with_provider(provider: Arc<dyn PushProvider>) -> Arc<PushService> {
    Arc::new(PushService::new(provider))
}

/// Convenience for the no-FCM case.
pub fn disabled() -> Arc<PushService> {
    Arc::new(PushService::disabled())
}

pub use fcm::FcmPushProvider;

/// Re-export for main.rs worker spawning convenience.
pub fn spawn_worker_if_enabled(service: Arc<PushService>, state: AppState) {
    if service.enabled() {
        service.spawn_worker(state.db.clone());
    }
}

/// Purges finished outbox rows even when push is disabled now, so rows left
/// from an earlier configuration do not stay forever.
pub fn spawn_cleanup(state: AppState) {
    outbox::spawn_cleanup(state.db.clone());
}

#[cfg(test)]
mod tests {
    use super::alert_concurrency;

    #[test]
    fn alert_concurrency_defaults_and_stays_below_the_pool() {
        assert_eq!(alert_concurrency(None, 10), 4);
        assert_eq!(alert_concurrency(Some(" 6 "), 12), 6);
        // Ten connections: five lanes, so at most five alert sends.
        assert_eq!(alert_concurrency(Some("12"), 10), 5);
        assert_eq!(alert_concurrency(Some("64"), 100), 16);
        assert_eq!(alert_concurrency(Some("0"), 10), 1);
        assert_eq!(alert_concurrency(Some("many"), 10), 4);
        assert_eq!(alert_concurrency(None, 3), 1);
    }
}

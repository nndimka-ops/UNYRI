pub mod fcm;
pub mod apns;
pub mod handlers;
pub mod models;
pub mod outbox;

pub use models::{PushError, PushEvent, PushPriority, PushProvider, PushSendOutcome};
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
    Arc::new(build_fcm(config).with_apns(apns::configured_providers()))
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

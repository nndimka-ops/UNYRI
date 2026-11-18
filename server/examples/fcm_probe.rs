// FCM infrastructure probe (Этап K live verification). Never prints the OAuth
// access token, the FCM token, or the service-account private key.
//
//   cargo run --example fcm_probe [device_fcm_token]
//
// Without an argument it only verifies the OAuth token exchange; with a device
// FCM token it additionally performs a harmless HTTP v1 `messages:send` test.

use std::sync::Arc;

use messenger_server::push::fcm::{FcmAccessTokenProvider, FcmPushProvider, ReqwestFcmTransport, ServiceAccountTokenSource};
use messenger_server::push::models::{Presentation, PushEvent, PushPriority, PushProvider};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok();

    let project_id = std::env::var("FCM_PROJECT_ID")?;
    let creds = std::env::var("FCM_SERVICE_ACCOUNT_FILE")
        .or_else(|_| std::env::var("GOOGLE_APPLICATION_CREDENTIALS"))?;

    let transport = Arc::new(ReqwestFcmTransport::new(reqwest::Client::new()));
    let source = ServiceAccountTokenSource::from_file(std::path::Path::new(&creds), transport.clone())?;
    let token_provider = FcmAccessTokenProvider::new(Arc::new(source));

    match token_provider.get_token().await {
        Ok(token) => {
            let ttl = token.expires_at.saturating_duration_since(std::time::Instant::now()).as_secs();
            println!("OAuth OK: access token acquired (ttl ~{ttl}s)");
        }
        Err(err) => {
            eprintln!("OAuth FAILED: {err}");
            std::process::exit(1);
        }
    }

    if let Some(device_token) = std::env::args().nth(1) {
        let provider = FcmPushProvider::new(project_id, token_provider, transport);
        let event = PushEvent {
            event: "probe".into(),
            data: Default::default(),
            priority: PushPriority::High,
            ttl_seconds: 60,
            collapse_key: None,
            presentation: Presentation::Alert,
        };
        match provider.send(&device_token, &event).await {
            Ok(outcome) => println!("FCM send outcome: {outcome:?}"),
            Err(err) => {
                eprintln!("FCM send FAILED: {err}");
                std::process::exit(1);
            }
        }
    }

    Ok(())
}

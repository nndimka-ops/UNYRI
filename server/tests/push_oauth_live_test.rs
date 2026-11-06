// Live infrastructure probe (ignored by default) - verifies the REAL
// service-account OAuth flow and, when a registration token is supplied via
// FCM_TEST_DEVICE_TOKEN, a real FCM HTTP v1 send. Never prints the OAuth
// token, FCM token, or private key.

use std::sync::Arc;

use messenger_server::push::fcm::{AccessTokenSource, FcmAccessTokenProvider, FcmPushProvider, ReqwestFcmTransport};
use messenger_server::push::models::{PushProvider, PushSendOutcome};

#[tokio::test]
#[ignore = "requires real Firebase service-account credentials"]
async fn real_service_account_oauth_flow_returns_a_token() {
    dotenvy::dotenv().ok();
    let path = std::env::var("FCM_SERVICE_ACCOUNT_FILE")
        .or_else(|_| std::env::var("GOOGLE_APPLICATION_CREDENTIALS"))
        .expect("set FCM_SERVICE_ACCOUNT_FILE to run this live probe");

    let transport = Arc::new(ReqwestFcmTransport::new(reqwest::Client::new()));
    let source = messenger_server::push::fcm::ServiceAccountTokenSource::from_file(std::path::Path::new(&path), transport)
        .expect("load service account");
    let token = source.fetch_token().await.expect("OAuth token fetch");

    // Never print the token - only masked metadata.
    assert!(!token.value.is_empty(), "OAuth access token must not be empty");
    eprintln!("OAUTH_OK token_length={}", token.value.len());
}

#[tokio::test]
#[ignore = "requires real Firebase credentials AND a registered device token"]
async fn real_fcm_http_v1_send_reaches_google() {
    dotenvy::dotenv().ok();
    let project_id = std::env::var("FCM_PROJECT_ID").expect("set FCM_PROJECT_ID");
    let device_token = std::env::var("FCM_TEST_DEVICE_TOKEN").expect("set FCM_TEST_DEVICE_TOKEN");
    let path = std::env::var("FCM_SERVICE_ACCOUNT_FILE")
        .or_else(|_| std::env::var("GOOGLE_APPLICATION_CREDENTIALS"))
        .expect("set FCM_SERVICE_ACCOUNT_FILE");

    let transport = Arc::new(ReqwestFcmTransport::new(reqwest::Client::new()));
    let source = messenger_server::push::fcm::ServiceAccountTokenSource::from_file(std::path::Path::new(&path), transport.clone())
        .expect("load service account");
    let tokens = FcmAccessTokenProvider::new(Arc::new(source));
    let provider = FcmPushProvider::new(project_id, tokens, transport);

    let event = messenger_server::push::models::PushEvent::new_message(
        uuid::Uuid::new_v4(),
        uuid::Uuid::new_v4(),
        1,
    );
    let outcome = provider.send(&device_token, &event).await.expect("FCM send");
    assert_eq!(outcome, PushSendOutcome::Delivered, "real FCM send should be delivered");
    eprintln!("FCM_SEND_OK");
}

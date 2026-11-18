// Unit tests for the FCM HTTP v1 client + OAuth token caching layer. Every
// network boundary is faked (FakeFcmTransport / FakeTokenSource) - no Google
// access, no DB.

mod push_common;

use std::sync::Arc;
use std::time::Duration;

use messenger_server::push::fcm::{
    FcmAccessTokenProvider, FcmPushProvider, ServiceAccountTokenSource,
};
use messenger_server::push::models::{PushEvent, PushPriority, PushProvider, PushSendOutcome};

use push_common::{FakeFcmTransport, FakeTokenSource};

fn audio_call_event() -> PushEvent {
    PushEvent::incoming_call(uuid::Uuid::new_v4(), "audio")
}

#[test]
fn endpoint_is_fcm_http_v1() {
    assert_eq!(
        FcmPushProvider::send_url("my-project"),
        "https://fcm.googleapis.com/v1/projects/my-project/messages:send"
    );
}

#[tokio::test]
async fn bearer_header_is_the_oauth_token() {
    let transport = Arc::new(FakeFcmTransport::new());
    transport.push_response(200, "{}");
    let token_source = Arc::new(FakeTokenSource::new());
    let provider = FcmPushProvider::new(
        "project".into(),
        FcmAccessTokenProvider::new(token_source.clone()),
        transport.clone(),
    );

    let outcome = provider
        .send("device-token", &audio_call_event())
        .await
        .unwrap();
    assert_eq!(outcome, PushSendOutcome::Delivered);

    let requests = transport.recorded();
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].bearer, "fake-token-1");
    assert_eq!(requests[0].url, FcmPushProvider::send_url("project"));
}

#[tokio::test]
async fn access_token_is_cached_between_sends() {
    let transport = Arc::new(FakeFcmTransport::new());
    transport.push_response(200, "{}");
    transport.push_response(200, "{}");
    let token_source = Arc::new(FakeTokenSource::new());
    let provider = FcmPushProvider::new(
        "project".into(),
        FcmAccessTokenProvider::new(token_source.clone()),
        transport.clone(),
    );

    provider.send("a", &audio_call_event()).await.unwrap();
    provider.send("b", &audio_call_event()).await.unwrap();

    // Two sends, one OAuth fetch - the token is cached.
    assert_eq!(token_source.count(), 1);
}

#[tokio::test]
async fn concurrent_sends_single_flight_token_refresh() {
    let transport = Arc::new(FakeFcmTransport::new());
    for _ in 0..8 {
        transport.push_response(200, "{}");
    }
    // Slow source: if refresh were not single-flight, 8 concurrent sends would
    // all block on the source and each fetch a token.
    let mut source = FakeTokenSource::new();
    source.delay = Some(Duration::from_millis(50));
    let token_source = Arc::new(source);
    let provider = Arc::new(FcmPushProvider::new(
        "project".into(),
        FcmAccessTokenProvider::new(token_source.clone()),
        transport.clone(),
    ));

    let mut handles = Vec::new();
    for i in 0..8 {
        let provider = provider.clone();
        handles.push(tokio::spawn(async move {
            provider
                .send(&format!("t{i}"), &audio_call_event())
                .await
                .unwrap()
        }));
    }
    for handle in handles {
        handle.await.unwrap();
    }

    assert_eq!(
        token_source.count(),
        1,
        "concurrent refresh must be single-flight"
    );
}

#[test]
fn transient_statuses_are_transient() {
    for status in [429u16, 500, 502, 503] {
        assert_eq!(
            FcmPushProvider::classify(status, "{}"),
            PushSendOutcome::Transient
        );
    }
}

#[test]
fn unregistered_is_invalid_token() {
    assert_eq!(
        FcmPushProvider::classify(
            404,
            r#"{"error":{"status":"NOT_FOUND","message":"","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}"#
        ),
        PushSendOutcome::InvalidToken
    );
}

#[test]
fn bad_payload_is_permanent_not_retry() {
    assert_eq!(
        FcmPushProvider::classify(400, r#"{"error":{"status":"INVALID_ARGUMENT"}}"#),
        PushSendOutcome::Permanent
    );
    assert_eq!(
        FcmPushProvider::classify(403, "{}"),
        PushSendOutcome::Permanent
    );
}

#[test]
fn call_payload_has_high_priority_short_ttl_and_no_secrets() {
    let provider = FcmPushProvider::new(
        "project".into(),
        FcmAccessTokenProvider::new(Arc::new(FakeTokenSource::new())),
        Arc::new(FakeFcmTransport::new()),
    );
    let event = PushEvent::incoming_call(uuid::Uuid::new_v4(), "video");
    let message = provider.build_message("reg-token", &event);
    let text = serde_json::to_string(&message).unwrap();

    assert!(text.contains("\"priority\":\"high\""));
    assert!(text.contains("\"ttl\":\"60s\""));
    // Never leak anything the client must not receive via FCM.
    for forbidden in [
        "sdp",
        "ice",
        "credential",
        "secret",
        "turn",
        "private",
        "plaintext",
        "signal",
    ] {
        assert!(
            !text.to_lowercase().contains(forbidden),
            "payload must not contain {forbidden}"
        );
    }
}

#[test]
fn message_payload_is_privacy_safe() {
    let provider = FcmPushProvider::new(
        "project".into(),
        FcmAccessTokenProvider::new(Arc::new(FakeTokenSource::new())),
        Arc::new(FakeFcmTransport::new()),
    );
    let event = PushEvent::new_message(uuid::Uuid::new_v4(), uuid::Uuid::new_v4(), 42);
    let message = provider.build_message("reg-token", &event);
    let text = serde_json::to_string(&message).unwrap();

    assert!(text.contains("\"priority\":\"high\""));
    assert!(text.contains("\"collapse_key\":\"chat:"));
    assert!(!text.contains("ciphertext"));
}

#[test]
fn admin_notice_has_visible_android_and_apns_notification() {
    let provider = FcmPushProvider::new(
        "project".into(),
        FcmAccessTokenProvider::new(Arc::new(FakeTokenSource::new())),
        Arc::new(FakeFcmTransport::new()),
    );
    let event = PushEvent::admin_notice(
        uuid::Uuid::new_v4(),
        "Security review",
        "Please review your active devices.",
        "warning",
    );
    let message = provider.build_message("reg-token", &event);

    assert_eq!(
        message["message"]["notification"]["title"],
        "Security review"
    );
    assert_eq!(
        message["message"]["notification"]["body"],
        "Please review your active devices."
    );
    assert_eq!(message["message"]["apns"]["headers"]["apns-priority"], "10");
    assert_eq!(
        message["message"]["apns"]["payload"]["aps"]["category"],
        "COCO_ADMIN_NOTICE"
    );
    assert_eq!(message["message"]["data"]["severity"], "warning");
}

#[tokio::test]
async fn service_account_errors_never_leak_the_private_key() {
    use messenger_server::push::fcm::AccessTokenSource;
    let private_key =
        "-----BEGIN PRIVATE KEY-----\nTOP-SECRET-KEY-MATERIAL\n-----END PRIVATE KEY-----";
    let json = format!(
        r#"{{"client_email":"svc@example.com","token_uri":"https://oauth2.googleapis.com/token","private_key":"{}"}}"#,
        private_key.replace('\n', "\\n")
    );
    let transport = Arc::new(FakeFcmTransport::new());
    let source = ServiceAccountTokenSource::from_json(&json, transport).unwrap();
    let err = source.fetch_token().await.unwrap_err();
    assert!(!err.to_string().contains("TOP-SECRET-KEY-MATERIAL"));
}

#[test]
fn normal_priority_default_on_plain_event() {
    // Defaults are applied by serde; construct via JSON to exercise defaults.
    let value: PushEvent = serde_json::from_str(r#"{"event":"new_message"}"#).unwrap();
    assert_eq!(value.priority, PushPriority::Normal);
    assert_eq!(value.ttl_seconds, 3600);
}

#[test]
fn user_visible_message_wakes_doze_without_plaintext() {
    let event = PushEvent::new_message(uuid::Uuid::new_v4(), uuid::Uuid::new_v4(), 7);
    assert_eq!(event.priority, PushPriority::High);
    assert_eq!(event.data.len(), 3);
    assert_eq!(event.data.get("sequence").unwrap(), "7");
    assert!(event.collapse_key.is_some());
}

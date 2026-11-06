// K.12 - message push dispatch tests (privacy-safe payloads, sender exclusion,
// push failure isolation from the message transaction).

mod push_common;

use std::sync::Arc;

use axum::extract::ConnectInfo;
use axum::http::StatusCode;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde_json::{json, Value};
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::push::models::{PushEvent, PushProvider, PushSendOutcome};
use messenger_server::state::AppState;

use push_common::*;

fn app(state: AppState) -> axum::Router {
    messenger_server::app(state)
}

fn envelope(recipient_device_id: &str, plaintext: &str) -> Value {
    json!({
        "recipient_device_id": recipient_device_id,
        "message_type": 2,
        "ciphertext": STANDARD.encode(plaintext.as_bytes()),
    })
}

async fn send_message(
    app: &axum::Router,
    ip: ConnectInfo<std::net::SocketAddr>,
    token: &str,
    chat_id: &str,
    client_message_id: Uuid,
    envelopes: Vec<Value>,
    kind: &str,
    target: Option<&str>,
) -> Value {
    let mut body = json!({ "client_message_id": client_message_id, "envelopes": envelopes, "kind": kind });
    if let Some(target) = target {
        body["target_message_id"] = json!(target);
    }
    let response = app
        .clone()
        .oneshot(post_json_request(&format!("/chats/{chat_id}/messages"), ip, token, body))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK, "message send should succeed");
    body_json(response).await
}

/// A provider that always errors - used to prove a push failure can never fail
/// the (already committed) message/call transaction.
struct FailingPushProvider;

#[async_trait::async_trait]
impl PushProvider for FailingPushProvider {
    async fn send(&self, _token: &str, _event: &PushEvent) -> Result<PushSendOutcome, messenger_server::push::PushError> {
        Err(messenger_server::push::PushError::Http(500))
    }
}

#[tokio::test]
async fn new_message_queues_recipient_push() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "msgsender").await;
    let (bob, bob_id, bob_dev) = register_user(&app, ip, "msgrecv").await;
    let chat_id = create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    let sent = send_message(&app, ip, &alice, &chat_id, Uuid::new_v4(), vec![envelope(&bob_dev, "hi")], "content", None).await;
    let sequence = sent["sequence"].as_i64().unwrap();

    let rows = outbox_rows(&state.db).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].1, "new_message");
    assert_eq!(rows[0].2["data"]["chat_id"].as_str().unwrap(), chat_id);
    assert_eq!(rows[0].2["data"]["sequence"].as_str().unwrap(), sequence.to_string());
}

#[tokio::test]
async fn message_push_payload_never_contains_plaintext() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "msgplain").await;
    let (bob, bob_id, bob_dev) = register_user(&app, ip, "msgplainb").await;
    let chat_id = create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    let secret = "TOP-SECRET-PLAINTEXT";
    send_message(&app, ip, &alice, &chat_id, Uuid::new_v4(), vec![envelope(&bob_dev, secret)], "content", None).await;

    let rows = outbox_rows(&state.db).await;
    let text = rows[0].2.to_string().to_lowercase();
    assert!(!text.contains(secret.to_lowercase().as_str()));
    assert!(!text.contains("ciphertext"));
    assert!(!text.contains("attachment"));
}

#[tokio::test]
async fn edit_and_reaction_payloads_carry_no_plaintext() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, alice_dev) = register_user(&app, ip, "msgedit").await;
    let (bob, bob_id, bob_dev) = register_user(&app, ip, "msgeditb").await;
    let chat_id = create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &alice, "alice-fcm").await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    let first = send_message(&app, ip, &alice, &chat_id, Uuid::new_v4(), vec![envelope(&bob_dev, "original")], "content", None).await;
    let first_id = first["id"].as_str().unwrap();

    sqlx::query("DELETE FROM push_outbox").execute(&state.db).await.unwrap();
    send_message(&app, ip, &alice, &chat_id, Uuid::new_v4(), vec![envelope(&bob_dev, "EDITED-TEXT")], "edit", Some(first_id)).await;
    let edit_text = outbox_rows(&state.db).await.last().unwrap().2.to_string().to_lowercase();
    assert!(!edit_text.contains("edited-text"));

    sqlx::query("DELETE FROM push_outbox").execute(&state.db).await.unwrap();
    send_message(&app, ip, &bob, &chat_id, Uuid::new_v4(), vec![envelope(&alice_dev, "😀")], "reaction", Some(first_id)).await;
    let reaction_text = outbox_rows(&state.db).await.last().unwrap().2.to_string().to_lowercase();
    assert!(!reaction_text.contains("emoji") && !reaction_text.contains("😀"));
}

#[tokio::test]
async fn sender_devices_are_excluded() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, alice_dev) = register_user(&app, ip, "msgself").await;
    let (bob, bob_id, bob_dev) = register_user(&app, ip, "msgselfb").await;
    let chat_id = create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &alice, "alice-fcm").await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    send_message(&app, ip, &alice, &chat_id, Uuid::new_v4(), vec![envelope(&bob_dev, "hi")], "content", None).await;

    let rows = outbox_rows(&state.db).await;
    assert_eq!(rows.len(), 1, "the sender's own device must not receive an incoming push");
    let _ = alice_dev;
}

#[tokio::test]
async fn push_failure_does_not_fail_message_send() {
    let (_lock, state) = locked_state(Arc::new(FailingPushProvider)).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "msgfail").await;
    let (bob, bob_id, bob_dev) = register_user(&app, ip, "msgfailb").await;
    let chat_id = create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    // Message commits (200) despite the push provider failing.
    let sent = send_message(&app, ip, &alice, &chat_id, Uuid::new_v4(), vec![envelope(&bob_dev, "hi")], "content", None).await;
    assert!(sent["sequence"].as_i64().is_some());

    // The outbox row survives as undelivered and is scheduled for retry.
    state.push.process_due(&state.db).await;
    let row: (i32, Option<chrono::DateTime<chrono::Utc>>) = sqlx::query_as(
        "SELECT attempt_count, delivered_at FROM push_outbox ORDER BY created_at DESC LIMIT 1",
    )
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(row.0, 1, "a failed send must be counted and retried, not marked delivered");
    assert!(row.1.is_none());
}

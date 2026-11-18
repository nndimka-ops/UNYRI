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
async fn edits_and_reactions_do_not_alert() {
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
    // Only new content alerts; an edit must not ring "New message" again.
    assert!(outbox_rows(&state.db).await.is_empty(), "edits must not enqueue an alert push");

    sqlx::query("DELETE FROM push_outbox").execute(&state.db).await.unwrap();
    send_message(&app, ip, &bob, &chat_id, Uuid::new_v4(), vec![envelope(&alice_dev, "😀")], "reaction", Some(first_id)).await;
    assert!(outbox_rows(&state.db).await.is_empty(), "reactions must not enqueue an alert push");
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

#[tokio::test]
async fn message_push_commits_with_the_message_and_is_queued_once() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "msgtx").await;
    let (bob, bob_id, bob_dev) = register_user(&app, ip, "msgtxb").await;
    let chat_id = create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    // Make the outbox insert fail for this chat only.
    let client = Uuid::new_v4();
    let hook = format!("fail_push_{}", client.simple());
    sqlx::query(&format!("CREATE FUNCTION {hook}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test push enqueue failure'; END $$"))
        .execute(&state.db).await.unwrap();
    sqlx::query(&format!("CREATE TRIGGER {hook} BEFORE INSERT ON push_outbox FOR EACH ROW WHEN (NEW.payload->'data'->>'chat_id' = '{chat_id}') EXECUTE FUNCTION {hook}()"))
        .execute(&state.db).await.unwrap();
    let uri = format!("/chats/{chat_id}/messages");
    let body = json!({ "client_message_id": client, "envelopes": [envelope(&bob_dev, "hi")], "kind": "content" });
    let failed = app.clone().oneshot(post_json_request(&uri, ip, &alice, body.clone())).await.unwrap();
    sqlx::query(&format!("DROP TRIGGER {hook} ON push_outbox")).execute(&state.db).await.unwrap();
    sqlx::query(&format!("DROP FUNCTION {hook}()")).execute(&state.db).await.unwrap();

    assert_eq!(failed.status(), StatusCode::INTERNAL_SERVER_ERROR, "a push that cannot be queued fails the send");
    let messages: i64 = sqlx::query_scalar("SELECT count(*) FROM messages WHERE client_message_id = $1")
        .bind(client).fetch_one(&state.db).await.unwrap();
    assert_eq!(messages, 0, "the message rolls back with its push, so the client's retry is a new send");
    assert_eq!(outbox_count(&state.db).await, 0);

    // The retry sends and queues; a duplicate of it (is_new=false) queues nothing more.
    let mut ids = Vec::new();
    for _ in 0..2 {
        let response = app.clone().oneshot(post_json_request(&uri, ip, &alice, body.clone())).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        ids.push(body_json(response).await["id"].clone());
    }
    assert_eq!(ids[0], ids[1], "the duplicate returns the same message");
    let rows = outbox_rows(&state.db).await;
    assert_eq!(rows.len(), 1, "one alert per recipient device, however often the send is retried");
    assert_eq!(rows[0].0.to_string(), bob_dev);
    assert_eq!(rows[0].2["data"]["message_id"], ids[0]);
}

#[tokio::test]
async fn muted_chat_queues_no_push_until_the_mute_expires() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "msgmute").await;
    let (bob, bob_id, bob_dev) = register_user(&app, ip, "msgmuteb").await;
    let chat_id = create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;
    let (chat, bob_user) = (Uuid::parse_str(&chat_id).unwrap(), Uuid::parse_str(&bob_id).unwrap());

    sqlx::query("INSERT INTO chat_preferences (chat_id, user_id, is_muted, muted_until) VALUES ($1, $2, TRUE, now() + interval '1 hour')")
        .bind(chat).bind(bob_user).execute(&state.db).await.unwrap();
    send_message(&app, ip, &alice, &chat_id, Uuid::new_v4(), vec![envelope(&bob_dev, "quiet")], "content", None).await;
    assert!(outbox_rows(&state.db).await.is_empty(), "a muted chat queues no alert");

    sqlx::query("UPDATE chat_preferences SET muted_until = now() - interval '1 minute' WHERE chat_id = $1 AND user_id = $2")
        .bind(chat).bind(bob_user).execute(&state.db).await.unwrap();
    send_message(&app, ip, &alice, &chat_id, Uuid::new_v4(), vec![envelope(&bob_dev, "loud")], "content", None).await;
    assert_eq!(outbox_rows(&state.db).await.len(), 1, "an expired mute alerts again");
}

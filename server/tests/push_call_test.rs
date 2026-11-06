// K.12 - call push dispatch tests (outbox enqueue + worker dispatch, faked FCM).

mod push_common;

use std::net::SocketAddr;
use std::sync::Arc;

use axum::extract::ConnectInfo;
use axum::http::StatusCode;
use serde_json::{json, Value};
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::push::models::{PushSendOutcome};
use messenger_server::state::AppState;

use push_common::*;

fn app(state: AppState) -> axum::Router {
    messenger_server::app(state)
}

async fn create_call(app: &axum::Router, ip: ConnectInfo<SocketAddr>, token: &str, callee: &str, kind: &str) -> Value {
    let response = app
        .clone()
        .oneshot(post_json_request("/calls", ip, token, json!({ "callee_user_id": callee, "media_kind": kind })))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    body_json(response).await
}

#[tokio::test]
async fn create_call_queues_push_for_callee_devices() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "caller").await;
    let (bob, bob_id, bob_dev) = register_user(&app, ip, "callee").await;
    create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm-token").await;

    let call = create_call(&app, ip, &alice, &bob_id, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    let rows = outbox_rows(&state.db).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].0, Uuid::parse_str(&bob_dev).unwrap());
    assert_eq!(rows[0].1, "incoming_call");
    assert_eq!(rows[0].2["data"]["call_id"].as_str().unwrap(), call_id);
    assert_eq!(rows[0].2["data"]["media_kind"].as_str().unwrap(), "audio");

    // Dispatch and verify the faked FCM received the wake.
    state.push.process_due(&state.db).await;
    let sent = fake.recorded();
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0].token, "bob-fcm-token");
    assert_eq!(sent[0].event.event, "incoming_call");
}

#[tokio::test]
async fn caller_device_is_excluded() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "callerx").await;
    let (bob, bob_id, _bob_dev) = register_user(&app, ip, "calleex").await;
    create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &alice, "alice-fcm").await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    create_call(&app, ip, &alice, &bob_id, "audio").await;

    // The caller (Alice) is never a push target for her own outgoing call.
    let rows = outbox_rows(&state.db).await;
    assert_eq!(rows.len(), 1, "only the callee's devices should be queued");
}

#[tokio::test]
async fn call_push_is_high_priority_short_ttl() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "callhi").await;
    let (bob, bob_id, _bob_dev) = register_user(&app, ip, "calleehi").await;
    create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    create_call(&app, ip, &alice, &bob_id, "video").await;

    let rows = outbox_rows(&state.db).await;
    assert_eq!(rows[0].2["priority"].as_str().unwrap(), "high");
    let ttl = rows[0].2["ttl_seconds"].as_u64().unwrap();
    assert!(ttl <= 60, "call TTL must be short (<= 60s), got {ttl}");
}

#[tokio::test]
async fn call_payload_never_contains_sdp_or_turn_secrets() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "callsec").await;
    let (bob, bob_id, _bob_dev) = register_user(&app, ip, "calleesec").await;
    create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    create_call(&app, ip, &alice, &bob_id, "audio").await;

    let rows = outbox_rows(&state.db).await;
    let text = rows[0].2.to_string().to_lowercase();
    for forbidden in ["sdp", "ice", "candidate", "turn", "secret", "credential"] {
        assert!(!text.contains(forbidden), "call push payload must not contain {forbidden}");
    }
}

#[tokio::test]
async fn cancel_queues_call_ended() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "callcancel").await;
    let (bob, bob_id, _bob_dev) = register_user(&app, ip, "calleecancel").await;
    create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    let call = create_call(&app, ip, &alice, &bob_id, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    // Clear the incoming_call rows so we can assert on the call_ended row alone.
    sqlx::query("DELETE FROM push_outbox").execute(&state.db).await.unwrap();

    let response = app.clone().oneshot(post_request(&format!("/calls/{call_id}/cancel"), ip, &alice)).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let rows = outbox_rows(&state.db).await;
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].1, "call_ended");
    assert_eq!(rows[0].2["data"]["reason"].as_str().unwrap(), "cancelled");
}

#[tokio::test]
async fn missed_call_queues_call_ended() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "callmiss").await;
    let (bob, bob_id, _bob_dev) = register_user(&app, ip, "calleemiss").await;
    create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    let call = create_call(&app, ip, &alice, &bob_id, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    // Age the ringing deadline into the past, then run the server sweep.
    sqlx::query("UPDATE calls SET ringing_deadline = now() - interval '1 second' WHERE id = $1")
        .bind(Uuid::parse_str(call_id).unwrap())
        .execute(&state.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM push_outbox").execute(&state.db).await.unwrap();

    messenger_server::calls::handlers::sweep_expired_calls(&state).await;

    let rows = outbox_rows(&state.db).await;
    let ended: Vec<_> = rows.iter().filter(|r| r.1 == "call_ended" && r.2["data"]["reason"] == "missed").collect();
    assert!(!ended.is_empty(), "missed sweep must queue call_ended/missed");
}

#[tokio::test]
async fn answered_elsewhere_pushes_losing_devices() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "caller2d").await;
    let (bob1, bob_id, bob1_dev, bob_username) = register_user_full(&app, ip, "callee2d").await;
    let bob2 = login_second_device(&app, ip, &bob_username).await;
    create_direct_chat(&app, ip, &alice, &bob_id).await;
    // Both callee devices have a push token; the losing one must be notified.
    register_push_token(&app, ip, &bob1, "bob1-fcm").await;
    register_push_token(&app, ip, &bob2, "bob2-fcm").await;

    let call = create_call(&app, ip, &alice, &bob_id, "audio").await;
    let call_id = call["id"].as_str().unwrap();

    // Bob's second device accepts; Bob's first device must be told via push.
    let response = app
        .clone()
        .oneshot(post_request(&format!("/calls/{call_id}/accept"), ip, &bob2))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let rows = outbox_rows(&state.db).await;
    let elsewhere: Vec<_> = rows
        .iter()
        .filter(|r| r.1 == "call_ended" && r.2["data"]["reason"] == "answered_elsewhere")
        .collect();
    assert_eq!(elsewhere.len(), 1);
    // The losing device is Bob's first device (bob1_dev), not the accepting one.
    assert_eq!(elsewhere[0].0, Uuid::parse_str(&bob1_dev).unwrap());
}

#[tokio::test]
async fn duplicate_outbox_processing_is_idempotent() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = app(state.clone());
    let ip = connect_info();
    let (alice, _alice_id, _alice_dev) = register_user(&app, ip, "callidem").await;
    let (bob, bob_id, _bob_dev) = register_user(&app, ip, "calleeidem").await;
    create_direct_chat(&app, ip, &alice, &bob_id).await;
    register_push_token(&app, ip, &bob, "bob-fcm").await;

    create_call(&app, ip, &alice, &bob_id, "audio").await;

    state.push.process_due(&state.db).await;
    state.push.process_due(&state.db).await;

    assert_eq!(fake.recorded().len(), 1, "delivered rows must never be resent");
}

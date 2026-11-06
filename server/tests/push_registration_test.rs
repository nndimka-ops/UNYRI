// K.12 - token registration API tests (auth-scoped, device-scoped).

mod push_common;

use std::sync::Arc;

use axum::http::StatusCode;
use serde_json::json;
use tower::ServiceExt;
use uuid::Uuid;

use messenger_server::push::models::PushSendOutcome;
use messenger_server::state::AppState;

use push_common::*;

fn app_with_state(state: AppState) -> axum::Router {
    messenger_server::app(state)
}

#[tokio::test]
async fn authenticated_device_registers_token() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let state = spawn_state_with_provider(fake.clone()).await;
    let app = app_with_state(state.clone());
    let ip = connect_info();
    let (_token, _user, device_id) = register_user(&app, ip, "reg").await;

    let response = app
        .clone()
        .oneshot(put_json_request(
            "/devices/me/push-token",
            ip,
            _token.as_str(),
            json!({ "provider": "fcm", "token": "reg-token-abc" }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = body_json(response).await;
    assert_eq!(body["device_id"].as_str().unwrap(), device_id);
    assert_eq!(body["provider"], "fcm");

    let (stored, invalidated) = push_token_row(&state.db, &device_id).await;
    assert_eq!(stored, "reg-token-abc");
    assert!(!invalidated);
}

#[tokio::test]
async fn token_replace_updates_existing_row() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let state = spawn_state_with_provider(fake.clone()).await;
    let app = app_with_state(state.clone());
    let ip = connect_info();
    let (token, _user, device_id) = register_user(&app, ip, "replace").await;

    register_push_token(&app, ip, &token, "old-token").await;
    register_push_token(&app, ip, &token, "new-token").await;

    let (stored, _) = push_token_row(&state.db, &device_id).await;
    assert_eq!(stored, "new-token");
    // Still exactly one row (UNIQUE device_id upsert).
    let count: (i64,) = sqlx::query_as("SELECT count(*) FROM push_tokens WHERE device_id = $1")
        .bind(Uuid::parse_str(&device_id).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count.0, 1);
}

#[tokio::test]
async fn registration_is_scoped_to_the_authenticated_device() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let state = spawn_state_with_provider(fake.clone()).await;
    let app = app_with_state(state.clone());
    let ip = connect_info();
    let (token_alice, _alice_user, alice_device) = register_user(&app, ip, "alice").await;
    let (token_bob, _bob_user, bob_device) = register_user(&app, ip, "bob").await;

    // Alice registers a token - it lands on Alice's device row only.
    register_push_token(&app, ip, &token_alice, "alice-fcm").await;
    let (alice_stored, _) = push_token_row(&state.db, &alice_device).await;
    assert_eq!(alice_stored, "alice-fcm");

    // Bob's device has no token yet.
    let bob_row: Option<(String,)> = sqlx::query_as("SELECT token FROM push_tokens WHERE device_id = $1")
        .bind(Uuid::parse_str(&bob_device).unwrap())
        .fetch_optional(&state.db)
        .await
        .unwrap();
    assert!(bob_row.is_none());

    // Bob's own registration never touches Alice's row.
    register_push_token(&app, ip, &token_bob, "bob-fcm").await;
    let (alice_after, _) = push_token_row(&state.db, &alice_device).await;
    assert_eq!(alice_after, "alice-fcm");
}

#[tokio::test]
async fn logout_invalidates_the_association() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let state = spawn_state_with_provider(fake.clone()).await;
    let app = app_with_state(state.clone());
    let ip = connect_info();
    let (token, _user, device_id) = register_user(&app, ip, "logout").await;
    register_push_token(&app, ip, &token, "to-invalidate").await;

    let response = app.clone().oneshot(post_request("/auth/logout", ip, &token)).await.unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    let (_stored, invalidated) = push_token_row(&state.db, &device_id).await;
    assert!(invalidated, "logout must invalidate the push token mapping");
}

#[tokio::test]
async fn token_refresh_updates_timestamp() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let state = spawn_state_with_provider(fake.clone()).await;
    let app = app_with_state(state.clone());
    let ip = connect_info();
    let (token, _user, device_id) = register_user(&app, ip, "refresh").await;
    register_push_token(&app, ip, &token, "refresh-1").await;

    let before: (chrono::DateTime<chrono::Utc>,) = sqlx::query_as("SELECT updated_at FROM push_tokens WHERE device_id = $1")
        .bind(Uuid::parse_str(&device_id).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();

    tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
    register_push_token(&app, ip, &token, "refresh-2").await;

    let after: (chrono::DateTime<chrono::Utc>,) = sqlx::query_as("SELECT updated_at FROM push_tokens WHERE device_id = $1")
        .bind(Uuid::parse_str(&device_id).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(after.0 > before.0, "updated_at must advance on refresh");
}

#[tokio::test]
async fn duplicate_registration_is_idempotent() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let state = spawn_state_with_provider(fake.clone()).await;
    let app = app_with_state(state.clone());
    let ip = connect_info();
    let (token, _user, device_id) = register_user(&app, ip, "dupe").await;

    register_push_token(&app, ip, &token, "same-token").await;
    register_push_token(&app, ip, &token, "same-token").await;
    register_push_token(&app, ip, &token, "same-token").await;

    let count: (i64,) = sqlx::query_as("SELECT count(*) FROM push_tokens WHERE device_id = $1")
        .bind(Uuid::parse_str(&device_id).unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count.0, 1);
}

#[tokio::test]
async fn rejects_unknown_provider() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let state = spawn_state_with_provider(fake.clone()).await;
    let app = app_with_state(state.clone());
    let ip = connect_info();
    let (token, _user, _device) = register_user(&app, ip, "badprov").await;

    let response = app
        .clone()
        .oneshot(put_json_request(
            "/devices/me/push-token",
            ip,
            &token,
            json!({ "provider": "unknown", "token": "x" }),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

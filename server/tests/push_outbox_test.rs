// K.12 - push outbox durability/worker tests (SKIP LOCKED claiming, retry
// scheduling, terminal failures, no double delivery).

mod push_common;

use std::sync::Arc;
use std::time::Duration;

use uuid::Uuid;

use messenger_server::push::models::{PushEvent, PushSendOutcome};

use push_common::*;

// Outbox durability/retry fixtures are deliberately independent of app-domain
// chats/calls. Domain events now enforce recipient privacy against real rows;
// message/call suites cover those with actual conversations.
fn transport_event(chat_id: Uuid, message_id: Uuid, sequence: i64) -> PushEvent {
    let mut event = PushEvent::new_message(chat_id, message_id, sequence);
    event.event = "test_outbox".into();
    event
}

#[tokio::test]
async fn enqueue_leaves_a_durable_pending_row() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_token, user_id, _device) = register_user(&app, ip, "durable").await;
    register_push_token(&app, ip, &_token, "tok").await;

    let event = transport_event(Uuid::new_v4(), Uuid::new_v4(), 1);
    state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None, &event).await;

    let row: (bool,) = sqlx::query_as("SELECT delivered_at IS NULL FROM push_outbox ORDER BY created_at DESC LIMIT 1")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(row.0, "enqueue must leave a pending (undelivered) row");
}

#[tokio::test]
async fn concurrent_workers_never_double_deliver() {
    let mut provider = FakePushProvider::new(PushSendOutcome::Delivered);
    provider.delay = Some(Duration::from_millis(100));
    let fake = Arc::new(provider);
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_token, user_id, _device) = register_user(&app, ip, "concurrent").await;
    register_push_token(&app, ip, &_token, "tok").await;

    let event = transport_event(Uuid::new_v4(), Uuid::new_v4(), 1);
    state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None, &event).await;

    let a = state.clone();
    let b = state.clone();
    let (ra, rb) = tokio::join!(a.push.process_due(&a.db), b.push.process_due(&b.db));
    assert_eq!(ra + rb, 1, "exactly one worker may claim the row");
    assert_eq!(fake.recorded().len(), 1, "a claimed row must be sent exactly once");
}

#[tokio::test]
async fn transient_failure_schedules_a_retry() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Transient));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_token, user_id, _device) = register_user(&app, ip, "transient").await;
    register_push_token(&app, ip, &_token, "tok").await;

    state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None, &transport_event(Uuid::new_v4(), Uuid::new_v4(), 1)).await;
    state.push.process_due(&state.db).await;

    let row: (i32, Option<chrono::DateTime<chrono::Utc>>, chrono::DateTime<chrono::Utc>) =
        sqlx::query_as("SELECT attempt_count, delivered_at, next_attempt_at FROM push_outbox ORDER BY created_at DESC LIMIT 1")
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(row.0, 1);
    assert!(row.1.is_none(), "transient failure must not mark delivered");
    assert!(row.2 > chrono::Utc::now(), "next attempt must be scheduled in the future");
}

#[tokio::test]
async fn unregistered_invalidates_token_and_finishes_row() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::InvalidToken));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_token, user_id, device_id) = register_user(&app, ip, "unreg").await;
    register_push_token(&app, ip, &_token, "tok").await;

    state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None, &transport_event(Uuid::new_v4(), Uuid::new_v4(), 1)).await;
    state.push.process_due(&state.db).await;

    let (invalidated, error_code): (bool, Option<String>) = sqlx::query_as(
        "SELECT invalidated_at IS NOT NULL, last_error_code FROM push_tokens WHERE device_id = $1",
    )
    .bind(Uuid::parse_str(&device_id).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(invalidated, "UNREGISTERED must invalidate the token");
    assert_eq!(error_code.as_deref(), Some("UNREGISTERED"));

    let row: (Option<chrono::DateTime<chrono::Utc>>,) = sqlx::query_as("SELECT delivered_at FROM push_outbox ORDER BY created_at DESC LIMIT 1")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(row.0.is_some(), "invalid token must finish the outbox row (terminal)");
}

#[tokio::test]
async fn delivered_rows_are_not_resent() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (_token, user_id, _device) = register_user(&app, ip, "delivered").await;
    register_push_token(&app, ip, &_token, "tok").await;

    state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None, &transport_event(Uuid::new_v4(), Uuid::new_v4(), 1)).await;
    state.push.process_due(&state.db).await;
    state.push.process_due(&state.db).await;

    assert_eq!(fake.recorded().len(), 1);
    let row: (Option<chrono::DateTime<chrono::Utc>>,) = sqlx::query_as("SELECT delivered_at FROM push_outbox ORDER BY created_at DESC LIMIT 1")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(row.0.is_some());
}

#[tokio::test]
async fn stale_unregistered_response_does_not_invalidate_replacement_token() {
    struct PausedProvider { started: tokio::sync::Notify, resume: tokio::sync::Notify }
    #[async_trait::async_trait]
    impl messenger_server::push::PushProvider for PausedProvider {
        async fn send(&self, _token: &str, _event: &PushEvent) -> Result<PushSendOutcome, messenger_server::push::PushError> {
            self.started.notify_one();
            self.resume.notified().await;
            Ok(PushSendOutcome::InvalidToken)
        }
    }
    let fake = Arc::new(PausedProvider { started: tokio::sync::Notify::new(), resume: tokio::sync::Notify::new() });
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, user_id, device_id) = register_user(&app, ip, "rotate").await;
    register_push_token(&app, ip, &token, "old-token").await;
    state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None,
        &transport_event(Uuid::new_v4(), Uuid::new_v4(), 1)).await;
    let worker = state.clone();
    let task = tokio::spawn(async move { worker.push.process_due(&worker.db).await });
    tokio::time::timeout(Duration::from_secs(5), fake.started.notified()).await.unwrap();
    register_push_token(&app, ip, &token, "replacement-token").await;
    fake.resume.notify_one();
    task.await.unwrap();
    let active: bool = sqlx::query_scalar("SELECT invalidated_at IS NULL FROM push_tokens WHERE device_id = $1")
        .bind(Uuid::parse_str(&device_id).unwrap()).fetch_one(&state.db).await.unwrap();
    assert!(active);
}

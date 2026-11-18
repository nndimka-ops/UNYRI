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

    let row: (i32, Option<chrono::DateTime<chrono::Utc>>, chrono::DateTime<chrono::Utc>, Option<String>) =
        sqlx::query_as("SELECT attempt_count, delivered_at, next_attempt_at, last_error_code FROM push_outbox ORDER BY created_at DESC LIMIT 1")
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(row.0, 1);
    assert!(row.1.is_none(), "transient failure must not mark delivered");
    assert!(row.2 > chrono::Utc::now(), "next attempt must be scheduled in the future");
    assert_eq!(row.3.as_deref(), Some("fcm_transient"), "a pending retry shows why it is pending");
}

#[tokio::test]
async fn retries_stop_at_the_deadline_and_keep_the_failure_code() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Transient));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, user_id, _device) = register_user(&app, ip, "deadline").await;
    register_push_token(&app, ip, &token, "tok").await;

    state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None, &transport_event(Uuid::new_v4(), Uuid::new_v4(), 1)).await;
    // One second of the one-hour retry window is left; the shortest backoff is two.
    sqlx::query("UPDATE push_outbox SET created_at = now() - interval '3599 seconds'")
        .execute(&state.db)
        .await
        .unwrap();
    state.push.process_due(&state.db).await;

    let (finished, code): (bool, Option<String>) =
        sqlx::query_as("SELECT delivered_at IS NOT NULL, last_error_code FROM push_outbox ORDER BY created_at DESC LIMIT 1")
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(finished, "a retry that would land after the deadline ends the row");
    assert_eq!(code.as_deref(), Some("fcm_transient"));
    assert_eq!(fake.recorded().len(), 1);
}

#[tokio::test]
async fn enqueue_wakes_the_worker_before_the_next_poll() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, user_id, _device) = register_user(&app, ip, "wake").await;
    register_push_token(&app, ip, &token, "tok").await;

    // The worker sweeps once at start, then waits on the 2 s poll or a wake.
    let worker = state.push.clone().spawn_worker(state.db.clone());
    tokio::time::sleep(Duration::from_millis(200)).await;
    state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None, &transport_event(Uuid::new_v4(), Uuid::new_v4(), 1)).await;
    let woken = tokio::time::timeout(Duration::from_millis(1500), async {
        while fake.recorded().is_empty() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await;
    worker.abort();
    let _ = worker.await;
    assert!(woken.is_ok(), "a committed enqueue must not wait for the next poll");
}

#[tokio::test]
async fn purge_removes_only_finished_rows_past_retention() {
    let fake = Arc::new(FakePushProvider::new(PushSendOutcome::Delivered));
    let (_lock, state) = locked_state(fake.clone()).await;
    let app = messenger_server::app(state.clone());
    let ip = connect_info();
    let (token, user_id, _device) = register_user(&app, ip, "purge").await;
    register_push_token(&app, ip, &token, "tok").await;
    for sequence in 1..=3 {
        state.push.enqueue_for_users(&state.db, &[Uuid::parse_str(&user_id).unwrap()], None, &transport_event(Uuid::new_v4(), Uuid::new_v4(), sequence)).await;
    }
    let ids: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM push_outbox ORDER BY created_at, id")
        .fetch_all(&state.db)
        .await
        .unwrap();
    assert_eq!(ids.len(), 3);
    let (old_finished, recent_finished, old_pending) = (ids[0], ids[1], ids[2]);
    sqlx::query("UPDATE push_outbox SET delivered_at = now() - interval '8 days' WHERE id = $1")
        .bind(old_finished).execute(&state.db).await.unwrap();
    sqlx::query("UPDATE push_outbox SET delivered_at = now() - interval '1 day' WHERE id = $1")
        .bind(recent_finished).execute(&state.db).await.unwrap();
    sqlx::query("UPDATE push_outbox SET created_at = now() - interval '30 days' WHERE id = $1")
        .bind(old_pending).execute(&state.db).await.unwrap();

    let removed = messenger_server::push::outbox::purge_finished(&state.db).await.unwrap();
    assert!(removed >= 1);
    let left: Vec<Uuid> = sqlx::query_scalar("SELECT id FROM push_outbox")
        .fetch_all(&state.db)
        .await
        .unwrap();
    assert!(!left.contains(&old_finished), "finished rows past retention are purged");
    assert!(left.contains(&recent_finished), "recent finished rows stay for diagnostics");
    assert!(left.contains(&old_pending), "a pending row is never purged, however old");
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

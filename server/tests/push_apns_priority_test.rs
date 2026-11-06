mod apns_common;
use apns_common::legacy::*;
use apns_common::*;
use messenger_server::push::{PushError, PushEvent, PushProvider, PushSendOutcome, PushService};
use serde_json::json;
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};
use tower::ServiceExt;
use uuid::Uuid;

struct PanicOnce {
    first: AtomicBool,
    attempts: std::sync::atomic::AtomicUsize,
}
#[async_trait::async_trait]
impl PushProvider for PanicOnce {
    async fn send(&self, _: &str, _: &PushEvent) -> Result<PushSendOutcome, PushError> {
        self.attempts.fetch_add(1, Ordering::SeqCst);
        assert!(
            !self.first.swap(false, Ordering::SeqCst),
            "synthetic delivery fault"
        );
        Ok(PushSendOutcome::Delivered)
    }
}

#[tokio::test]
async fn delivery_task_error_releases_lane_and_keeps_failed_row_leased() {
    let (_guard, mut state, _, _) = state().await;
    let provider = Arc::new(PanicOnce {
        first: AtomicBool::new(true),
        attempts: std::sync::atomic::AtomicUsize::new(0),
    });
    state.push = Arc::new(PushService::new(provider.clone()));
    let app = messenger_server::app(state.clone());
    let (sender, _, _) = register_user(&app, connect_info(), "panicfrom").await;
    let (access, user, _) = register_user(&app, connect_info(), "panicto").await;
    let chat: Uuid = create_direct_chat(&app, connect_info(), &sender, &user)
        .await
        .parse()
        .unwrap();
    register_push_token(&app, connect_info(), &access, "owned-panic-target").await;
    for _ in 0..2 {
        state
            .push
            .enqueue_for_users(
                &state.db,
                &[user.parse().unwrap()],
                None,
                &PushEvent::new_message(chat, Uuid::new_v4(), 1),
            )
            .await;
    }
    assert_eq!(state.push.process_due(&state.db).await, 2);
    assert_eq!(provider.attempts.load(Ordering::SeqCst), 2);
    let leased: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM push_outbox WHERE delivered_at IS NULL AND next_attempt_at>now()",
    )
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(leased, 1);
    sqlx::query("UPDATE push_outbox SET next_attempt_at=now() WHERE delivered_at IS NULL")
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(state.push.process_due(&state.db).await, 1);
    assert_eq!(provider.attempts.load(Ordering::SeqCst), 3);
    assert_eq!(state.push.process_due(&state.db).await, 0);
}

#[tokio::test]
async fn cancelling_drain_releases_permit_without_early_reclaim_or_detached_finish() {
    let (_guard, mut state, _, _) = state().await;
    let provider = Arc::new(HeldFcm {
        bulk: Hold::new(),
        call: Hold::new(),
        hold_bulk: AtomicBool::new(true),
        hold_call: AtomicBool::new(false),
        events: Mutex::new(Vec::new()),
    });
    state.push = Arc::new(PushService::new(provider.clone()));
    let app = messenger_server::app(state.clone());
    let (sender, _, _) = register_user(&app, connect_info(), "cancelvia").await;
    let (access, user, _) = register_user(&app, connect_info(), "cancelto").await;
    let chat: Uuid = create_direct_chat(&app, connect_info(), &sender, &user)
        .await
        .parse()
        .unwrap();
    register_push_token(&app, connect_info(), &access, "owned-cancel-target").await;
    for _ in 0..2 {
        state
            .push
            .enqueue_for_users(
                &state.db,
                &[user.parse().unwrap()],
                None,
                &PushEvent::new_message(chat, Uuid::new_v4(), 1),
            )
            .await;
    }
    let worker_state = state.clone();
    let worker = tokio::spawn(async move { worker_state.push.process_due(&worker_state.db).await });
    tokio::time::timeout(Duration::from_secs(5), provider.bulk.entered.notified())
        .await
        .unwrap();
    worker.abort();
    assert!(worker.await.unwrap_err().is_cancelled());
    provider.hold_bulk.store(false, Ordering::SeqCst);
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if state.push.process_due(&state.db).await == 1 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(provider.events.lock().unwrap().len(), 2);
    let pending:i64=sqlx::query_scalar("SELECT count(*) FROM push_outbox WHERE delivered_at IS NULL AND attempt_count=1 AND next_attempt_at>now()").fetch_one(&state.db).await.unwrap();
    assert_eq!(pending, 1);
    // The cancelled first attempt is not magically acknowledged or reclaimed.
    assert_eq!(state.push.process_due(&state.db).await, 0);
    sqlx::query("UPDATE push_outbox SET next_attempt_at=now() WHERE delivered_at IS NULL")
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(state.push.process_due(&state.db).await, 1);
    assert_eq!(provider.events.lock().unwrap().len(), 3);
}

struct HeldFcm {
    bulk: Arc<Hold>,
    call: Arc<Hold>,
    hold_bulk: AtomicBool,
    hold_call: AtomicBool,
    events: Mutex<Vec<String>>,
}
#[async_trait::async_trait]
impl PushProvider for HeldFcm {
    async fn send(&self, _token: &str, event: &PushEvent) -> Result<PushSendOutcome, PushError> {
        self.events.lock().unwrap().push(event.event.clone());
        if event.event == "new_message" && self.hold_bulk.load(Ordering::SeqCst) {
            self.bulk.entered.notify_one();
            self.bulk.resume.notified().await;
        }
        if event.event == "incoming_call" && self.hold_call.load(Ordering::SeqCst) {
            self.call.entered.notify_one();
            self.call.resume.notified().await;
        }
        Ok(PushSendOutcome::Delivered)
    }
}

async fn wait_query(state: &messenger_server::state::AppState, sql: &str) {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if sqlx::query_scalar::<_, bool>(sql)
                .fetch_one(&state.db)
                .await
                .unwrap()
            {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
}

#[tokio::test]
async fn held_fcm_cannot_starve_live_voip_and_each_lane_keeps_one_owned_attempt() {
    let (_guard, mut state, transport, _) = state().await;
    let fcm = Arc::new(HeldFcm {
        bulk: Hold::new(),
        call: Hold::new(),
        hold_bulk: AtomicBool::new(true),
        hold_call: AtomicBool::new(true),
        events: Mutex::new(Vec::new()),
    });
    state.push =
        Arc::new(PushService::new(fcm.clone()).with_apns(vec![provider(transport.clone())]));
    let app = messenger_server::app(state.clone());
    let (caller, _, _) = register_user(&app, connect_info(), "callera").await;
    let (bulk_access, bulk_user, _) = register_user(&app, connect_info(), "bulk").await;
    let bulk_chat: Uuid = create_direct_chat(&app, connect_info(), &caller, &bulk_user)
        .await
        .parse()
        .unwrap();
    register_push_token(&app, connect_info(), &bulk_access, "owned-bulk-fcm").await;
    let ios_callee = ios(&app).await;
    create_direct_chat(&app, connect_info(), &caller, &ios_callee.user.to_string()).await;
    assert!(
        put(&app, &ios_callee, intent("apns_voip", Uuid::new_v4(), 1))
            .await
            .status()
            .is_success()
    );
    let (android_caller, _, _) = register_user(&app, connect_info(), "callerb").await;
    let (android_callee, android_user, _) = register_user(&app, connect_info(), "callee").await;
    create_direct_chat(&app, connect_info(), &android_caller, &android_user).await;
    register_push_token(&app, connect_info(), &android_callee, "owned-call-fcm").await;
    for _ in 0..4 {
        state
            .push
            .enqueue_for_users(
                &state.db,
                &[bulk_user.parse().unwrap()],
                None,
                &PushEvent::new_message(bulk_chat, Uuid::new_v4(), 1),
            )
            .await;
    }
    let worker_state = state.clone();
    let worker = tokio::spawn(async move { worker_state.push.process_due(&worker_state.db).await });
    tokio::time::timeout(Duration::from_secs(5), fcm.bulk.entered.notified())
        .await
        .unwrap();
    // Enqueue both live calls only AFTER an actual ordinary FCM send is held.
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/calls",
            connect_info(),
            &android_caller,
            json!({"callee_user_id":android_user,"media_kind":"audio"}),
        ))
        .await
        .unwrap();
    assert!(response.status().is_success());
    let android_call = body_json(response).await;
    let voip_hold = Hold::new();
    *transport.hold.lock().unwrap() = Some(voip_hold.clone());
    transport.response(503, json!({"reason":"ServiceUnavailable"}));
    let response = app
        .clone()
        .oneshot(post_json_request(
            "/calls",
            connect_info(),
            &caller,
            json!({"callee_user_id":ios_callee.user,"media_kind":"audio"}),
        ))
        .await
        .unwrap();
    assert!(response.status().is_success());
    let native_call = body_json(response).await;
    tokio::time::timeout(Duration::from_secs(5), voip_hold.entered.notified())
        .await
        .expect("live VoIP must be attempted before ordinary FCM is released");
    tokio::time::timeout(Duration::from_secs(5), fcm.call.entered.notified())
        .await
        .unwrap();
    assert!(fcm.hold_bulk.load(Ordering::SeqCst));
    assert_eq!(transport.count(), 1);
    let claimed: i64 = sqlx::query_scalar("SELECT count(*) FROM push_outbox WHERE attempt_count>0")
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(claimed, 3);
    let duplicate = tokio::time::timeout(
        Duration::from_millis(500),
        state.push.process_due(&state.db),
    )
    .await
    .unwrap();
    assert_eq!(duplicate, 0);
    let request = transport.requests.lock().unwrap();
    let payload: serde_json::Value = serde_json::from_slice(&request[0].payload).unwrap();
    assert!(payload["sent_at_ms"].as_i64().unwrap() < payload["expires_at_ms"].as_i64().unwrap());
    assert_eq!(request[0].push_type, "voip");
    assert_eq!(request[0].expiration, 0);
    drop(request);
    // Cancelled calls cannot be made fresh again by retry or by the free lane.
    for (access, call) in [(&caller, &native_call), (&android_caller, &android_call)] {
        let response = app
            .clone()
            .oneshot(post_request(
                &format!("/calls/{}/cancel", call["id"].as_str().unwrap()),
                connect_info(),
                access,
            ))
            .await
            .unwrap();
        assert!(response.status().is_success());
    }
    *transport.hold.lock().unwrap() = None;
    voip_hold.resume.notify_one();
    wait_query(&state,"SELECT EXISTS(SELECT 1 FROM push_outbox WHERE provider='apns_voip' AND delivered_at IS NULL AND next_attempt_at>now()+interval '800 seconds')").await;
    sqlx::query("UPDATE push_outbox SET next_attempt_at=now() WHERE provider='apns_voip' AND delivered_at IS NULL").execute(&state.db).await.unwrap();
    wait_query(&state,"SELECT EXISTS(SELECT 1 FROM push_outbox WHERE provider='apns_voip' AND delivered_at IS NOT NULL AND last_error_code='call_no_longer_ringing')").await;
    assert_eq!(transport.count(), 1);
    fcm.hold_call.store(false, Ordering::SeqCst);
    fcm.call.resume.notify_one();
    fcm.hold_bulk.store(false, Ordering::SeqCst);
    fcm.bulk.resume.notify_one();
    tokio::time::timeout(Duration::from_secs(5), worker)
        .await
        .unwrap()
        .unwrap();
    let events = fcm.events.lock().unwrap();
    assert_eq!(
        events
            .iter()
            .filter(|e| e.as_str() == "new_message")
            .count(),
        4
    );
    assert_eq!(
        events
            .iter()
            .filter(|e| e.as_str() == "incoming_call")
            .count(),
        1
    );
    assert_eq!(
        events.iter().filter(|e| e.as_str() == "call_ended").count(),
        1
    );
    let pending: i64 =
        sqlx::query_scalar("SELECT count(*) FROM push_outbox WHERE delivered_at IS NULL")
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(pending, 0);
}

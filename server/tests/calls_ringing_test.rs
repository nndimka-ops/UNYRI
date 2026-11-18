//! Ringing confirmation (docs/CALL_RINGING_SPEC.md 7.1): the caller learns one
//! bit, "some callee device is ringing", and nothing else. The kill switch is
//! tested in calls_ringing_off_test.rs, because it is read once per process.

mod calls_ringing_common;
mod apns_common;
#[path = "calls_ringing_common/redis_stall.rs"]
mod redis_stall;

use std::io::Write;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use axum::http::StatusCode;
use serde_json::{json, Value};
use uuid::Uuid;

use calls_ringing_common::*;

const PEER_RINGING: &str = "call.peer_ringing";

struct Pair {
    state: messenger_server::state::AppState,
    app: axum::Router,
    addr: std::net::SocketAddr,
    ip: axum::extract::ConnectInfo<std::net::SocketAddr>,
    alice: User,
    bob: User,
}

/// Alice (caller) and Bob (callee) with a direct chat, and a live server.
async fn pair(prefix: &str) -> Pair {
    pair_with(spawn_state().await, prefix).await
}

async fn pair_with(state: messenger_server::state::AppState, prefix: &str) -> Pair {
    let app = messenger_server::app(state.clone());
    let addr = spawn_server(state.clone()).await;
    let ip = connect_info();
    let alice = register_user(&app, ip, &format!("{prefix}a_")).await;
    let bob = register_user(&app, ip, &format!("{prefix}b_")).await;
    // Default fixture: a current build that advertised on a previous connection.
    // Its capability persists while offline; live reachability is irrelevant.
    messenger_server::calls::ringing::record_capability(&state.db, bob.device_uuid(), true).await;
    create_direct_chat(&app, ip, &alice, &bob).await;
    Pair { state, app, addr, ip, alice, bob }
}

async fn post_ok(p: &Pair, user: &User, uri: &str) -> Value {
    let (status, body) = call(&p.app, "POST", uri, p.ip, &user.token, None).await;
    assert_eq!(status, StatusCode::OK, "POST {uri}: {body}");
    body
}

#[tokio::test]
async fn database_wait_after_the_user_limiter_does_not_shorten_the_confirmation_deadline() {
    let p = pair("ring_db_wait_").await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    // Warm the user script before the proxy starts withholding its response.
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &Uuid::new_v4().to_string()).await.0, StatusCode::NOT_FOUND);
    let mut proxy = redis_stall::RedisStall::new(p.state.redis.clone(), "ratelimit:calls_ringing:user:", true).await;
    let mut state = p.state.clone();
    state.db = sqlx::postgres::PgPoolOptions::new().max_connections(1)
        .connect(&p.state.config.database_url).await.unwrap();
    state.redis = proxy.client.clone();
    let app = messenger_server::app(state.clone());
    sqlx::query("UPDATE calls SET ringing_deadline = clock_timestamp() + interval '6 seconds' WHERE id=$1")
        .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
    let confirmation = tokio::spawn({
        let (app, ip, bob, id) = (app.clone(), p.ip, p.bob.clone(), id.clone());
        async move { confirm(&app, ip, &bob, &id).await }
    });
    proxy.wait_stalled().await;
    // Authentication has finished. Occupy the only connection before allowing
    // the Redis reply through, forcing the initial call read to wait for it.
    let connection = state.db.acquire().await.unwrap();
    proxy.release().await;
    tokio::time::sleep(Duration::from_millis(3500)).await;
    assert!(!confirmation.is_finished(), "initial call read is waiting for the database pool");
    assert_eq!(bits(&p.state.db, &id).await, (true, false));
    drop(connection);
    let (status, body) = tokio::time::timeout(Duration::from_secs(2), confirmation).await.unwrap().unwrap();
    assert_eq!(status, StatusCode::OK, "the call still has ringing time after the database wait: {body}");
    assert_eq!(bits(&p.state.db, &id).await, (true, true));

    let remaining_ms: i64 = sqlx::query_scalar(
        "SELECT FLOOR(EXTRACT(EPOCH FROM (ringing_deadline - clock_timestamp())) * 1000)::bigint FROM calls WHERE id=$1",
    ).bind(Uuid::parse_str(&id).unwrap()).fetch_one(&p.state.db).await.unwrap();
    let ttl_ms: i64 = redis::cmd("PTTL").arg(confirmation_key(&p.bob, &id))
        .query_async(&mut p.state.redis.clone()).await.unwrap();
    assert!(remaining_ms > 1000, "fixture is still well within its deadline: {remaining_ms}");
    assert!(ttl_ms > remaining_ms - 200 && ttl_ms <= remaining_ms + 50,
        "counter expires at the ringing deadline, preserving its budget: ttl={ttl_ms}, remaining={remaining_ms}");
    for _ in 1..10 {
        assert_eq!(confirm(&app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    }
    assert_eq!(confirm(&app, p.ip, &p.bob, &id).await.0, StatusCode::TOO_MANY_REQUESTS);
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
    assert_no_confirmation_key(&p, &p.bob, &id).await;
}

#[tokio::test]
async fn slow_statement_preparation_does_not_reject_an_unexpired_confirmation() {
    let p = pair("ring_prepare_").await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &Uuid::new_v4().to_string()).await.0, StatusCode::NOT_FOUND);
    let mut proxy = redis_stall::RedisStall::new(p.state.redis.clone(), "ratelimit:calls_ringing:user:", true).await;
    let mut state = p.state.clone();
    // A fresh connection and disabled cache guarantee that the SELECT must
    // complete Parse/Describe before SQLx sends Bind/Execute.
    let options = p.state.config.database_url.parse::<sqlx::postgres::PgConnectOptions>().unwrap()
        .statement_cache_capacity(0);
    state.db = sqlx::postgres::PgPoolOptions::new().max_connections(1)
        .connect_with(options).await.unwrap();
    state.redis = proxy.client.clone();
    let backend: i32 = sqlx::query_scalar("SELECT pg_backend_pid()")
        .fetch_one(&state.db).await.unwrap();
    let app = messenger_server::app(state);
    sqlx::query("UPDATE calls SET ringing_deadline = clock_timestamp() + interval '6 seconds' WHERE id=$1")
        .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
    let confirmation = tokio::spawn({
        let (ip, bob, id) = (p.ip, p.bob.clone(), id.clone());
        async move { confirm(&app, ip, &bob, &id).await }
    });
    proxy.wait_stalled().await;
    let mut blocker = p.state.db.begin().await.unwrap();
    sqlx::query("LOCK TABLE calls IN ACCESS EXCLUSIVE MODE").execute(&mut *blocker).await.unwrap();
    proxy.release().await;
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let preparing: bool = sqlx::query_scalar(
                "SELECT COALESCE(wait_event_type='Lock', false) AND query LIKE '%FROM calls WHERE id = $1%' \
                 FROM pg_stat_activity WHERE pid=$1",
            ).bind(backend).fetch_one(&p.state.db).await.unwrap();
            if preparing { break; }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }).await.expect("the call SELECT is blocked during preparation, after acquiring its connection");
    tokio::time::sleep(Duration::from_millis(3500)).await;
    assert!(!confirmation.is_finished());
    blocker.commit().await.unwrap();
    let valid: bool = sqlx::query_scalar("SELECT state='ringing' AND ringing_deadline > clock_timestamp() FROM calls WHERE id=$1")
        .bind(Uuid::parse_str(&id).unwrap()).fetch_one(&p.state.db).await.unwrap();
    assert!(valid, "the preparation wait ended before the actual ringing deadline");
    let (status, body) = tokio::time::timeout(Duration::from_secs(2), confirmation).await.unwrap().unwrap();
    assert_eq!(status, StatusCode::OK, "preparation must not turn a valid confirmation into a 409: {body}");
    assert_eq!(bits(&p.state.db, &id).await, (true, true));
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
    assert_no_confirmation_key(&p, &p.bob, &id).await;
}

#[tokio::test]
async fn redis_stall_does_not_hold_a_connection_or_row_lock_or_recreate_a_terminal_counter() {
    for (terminal, after_execution) in [("accept", false), ("cancel", false), ("accept", true)] {
        let p = pair("ring_redis_lock_").await;
        let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
        // Warm the real Lua script: release must execute the delayed limiter,
        // rather than merely receiving NOSCRIPT after its future was dropped.
        assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
        let mut proxy = redis_stall::RedisStall::new(p.state.redis.clone(), "ratelimit:calls_ringing:call:", after_execution).await;
        let mut stalled_state = p.state.clone();
        // A one-connection pool makes a retained connection observable as well
        // as a row lock. The terminal request below uses this very same pool.
        stalled_state.db = sqlx::postgres::PgPoolOptions::new().max_connections(1)
            .connect(&p.state.config.database_url).await.unwrap();
        stalled_state.redis = proxy.client.clone();
        let stalled_app = messenger_server::app(stalled_state.clone());
        let confirmation = tokio::spawn({
            let (app, ip, bob, id) = (stalled_app, p.ip, p.bob.clone(), id.clone());
            async move { confirm(&app, ip, &bob, &id).await }
        });
        proxy.wait_stalled().await;
        let mut terminal_state = stalled_state.clone();
        terminal_state.redis = p.state.redis.clone();
        let terminal_app = messenger_server::app(terminal_state);
        let actor = if terminal == "accept" { &p.bob } else { &p.alice };
        let (status, _) = tokio::time::timeout(Duration::from_millis(200),
            call(&terminal_app, "POST", &format!("/calls/{id}/{terminal}"), p.ip, &actor.token, None))
            .await.expect("terminal action completes while confirmation is still waiting on Redis");
        assert_eq!(status, StatusCode::OK);
        assert!(!confirmation.is_finished(), "terminal completed before the Redis deadline released the confirmation");
        assert_eq!(tokio::time::timeout(Duration::from_secs(1), confirmation).await.unwrap().unwrap().0,
            StatusCode::INTERNAL_SERVER_ERROR, "limiter fails closed within its deadline");
        proxy.release().await;
        assert_no_confirmation_key(&p, &p.bob, &id).await;
        assert_eq!(bits(&p.state.db, &id).await, (false, false));
        if terminal == "accept" { post_ok(&p, &p.alice, &format!("/calls/{id}/hangup")).await; }
    }
}

#[tokio::test]
async fn redis_stall_cannot_recreate_a_counter_after_its_command_deadline() {
    let p = pair("ring_redis_expiry_").await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    sqlx::query("UPDATE calls SET ringing_deadline = clock_timestamp() + interval '800 milliseconds' WHERE id=$1")
        .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    let mut proxy = redis_stall::RedisStall::new(p.state.redis.clone(), "ratelimit:calls_ringing:call:", false).await;
    let mut state = p.state.clone();
    state.redis = proxy.client.clone();
    let app = messenger_server::app(state);
    let confirmation = tokio::spawn({
        let (ip, bob, id) = (p.ip, p.bob.clone(), id.clone());
        async move { confirm(&app, ip, &bob, &id).await }
    });
    proxy.wait_stalled().await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), confirmation).await.unwrap().unwrap().0,
        StatusCode::INTERNAL_SERVER_ERROR);
    tokio::time::sleep(Duration::from_millis(850)).await;
    // No terminal cleanup/marker: the Redis-only command deadline must suffice.
    proxy.release().await;
    assert_no_confirmation_key(&p, &p.bob, &id).await;
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
}

#[tokio::test]
async fn redis_stall_bounds_the_user_backstop_too() {
    let p = pair("ring_redis_user_").await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &Uuid::new_v4().to_string()).await.0, StatusCode::NOT_FOUND);
    let mut proxy = redis_stall::RedisStall::new(p.state.redis.clone(), "ratelimit:calls_ringing:user:", true).await;
    let mut state = p.state.clone();
    state.redis = proxy.client.clone();
    let app = messenger_server::app(state);
    let confirmation = tokio::spawn({
        let (ip, bob, id) = (p.ip, p.bob.clone(), id.clone());
        async move { confirm(&app, ip, &bob, &id).await }
    });
    proxy.wait_stalled().await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), confirmation).await.unwrap().unwrap().0,
        StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(bits(&p.state.db, &id).await, (true, false));
    assert_no_confirmation_key(&p, &p.bob, &id).await;
    proxy.release().await;
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
}

#[tokio::test]
async fn redis_stall_does_not_block_accept_end_cancel_signaling_or_the_sequential_sweep() {
    let p = pair("ring_redis_events_").await;
    let mut alice_ws = connect_legacy(p.addr, &p.state, &p.alice).await;
    let mut bob_ws = connect_capable(p.addr, &p.state, &p.bob).await;
    let proxy = redis_stall::RedisStall::new(p.state.redis.clone(), "ratelimit:calls_ringing:call:", false).await;
    let mut state = p.state.clone();
    state.redis = proxy.client.clone();
    let app = messenger_server::app(state);
    let mut terminal_ids = Vec::new();
    for terminal in ["accept", "hangup", "cancel"] {
        let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
        sqlx::query("UPDATE calls SET ringing_deadline=clock_timestamp()+interval '2 seconds' WHERE id=$1")
            .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
        assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
        terminal_ids.push(id.clone());
        let actor = if terminal == "accept" { &p.bob } else { &p.alice };
        let (status, _) = tokio::time::timeout(Duration::from_secs(1),
            call(&app, "POST", &format!("/calls/{id}/{terminal}"), p.ip, &actor.token, None))
            .await.expect("terminal signaling proceeds without Redis replies");
        assert_eq!(status, StatusCode::OK);
        let event = if terminal == "accept" { "call.accepted" } else { "call.ended" };
        let ws = if terminal == "accept" { &mut alice_ws } else { &mut bob_ws };
        let frame = next_of_type(ws, event, Duration::from_secs(1)).await.expect("participant notified");
        assert_eq!(frame["call_id"], id.as_str());
        if terminal == "accept" {
            let frame = next_of_type(&mut bob_ws, event, Duration::from_secs(1)).await.expect("accepting device notified");
            assert_eq!(frame["call_id"], id.as_str());
            // End the accepted call through the same stalled cleanup path.
            let (status, _) = tokio::time::timeout(Duration::from_secs(1),
                call(&app, "POST", &format!("/calls/{id}/hangup"), p.ip, &p.alice.token, None)).await.unwrap();
            assert_eq!(status, StatusCode::OK);
            assert_eq!(next_of_type(&mut bob_ws, "call.ended", Duration::from_secs(1)).await.unwrap()["call_id"], id.as_str());
        }
    }
    // Two expired calls prove that cleanup of the first cannot stop the sweep.
    let q = pair("ring_redis_sweep_").await;
    let first = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    let second = call_id(&create_call(&q.app, q.ip, &q.alice, &q.bob).await);
    let ids = vec![Uuid::parse_str(&first).unwrap(), Uuid::parse_str(&second).unwrap()];
    sqlx::query("UPDATE calls SET ringing_deadline=now()-interval '1 second' WHERE id=ANY($1)")
        .bind(&ids).execute(&p.state.db).await.unwrap();
    let mut sweep_state = p.state.clone();
    sweep_state.redis = proxy.client.clone();
    // The integration database is shared with other suites and prior runs.
    // Allow one bounded cleanup per active fixture, rather than assuming these
    // are the only expired calls selected by the global sweep.
    let active: i64 = sqlx::query_scalar("SELECT count(*) FROM calls WHERE state <> 'ended'")
        .fetch_one(&p.state.db).await.unwrap();
    let budget = Duration::from_millis(250 * active as u64) + Duration::from_secs(3);
    tokio::time::timeout(budget, messenger_server::calls::handlers::sweep_expired_calls(&sweep_state))
        .await.expect("sweep progresses past stalled cleanup");
    let ended: i64 = sqlx::query_scalar("SELECT count(*) FROM calls WHERE id=ANY($1) AND state='ended' AND end_reason='missed'")
        .bind(&ids).fetch_one(&p.state.db).await.unwrap();
    assert_eq!(ended, 2);
    let busy: i64 = sqlx::query_scalar("SELECT count(*) FROM call_participants_active WHERE call_id=ANY($1)")
        .bind(&ids).fetch_one(&p.state.db).await.unwrap();
    assert_eq!(busy, 0);
    assert_eq!(next_of_type(&mut bob_ws, "call.ended", Duration::from_secs(1)).await.unwrap()["call_id"], first.as_str());
    tokio::time::sleep(Duration::from_millis(2100)).await;
    for id in terminal_ids {
        assert_no_confirmation_key(&p, &p.bob, &id).await;
    }
    proxy.release().await;
}

// ---------------------------------------------------------------------------
// 1. The expectation bit at create
// ---------------------------------------------------------------------------

#[tokio::test]
async fn expectation_true_when_every_callee_device_is_capable_even_offline() {
    let p = pair("ring_exp_all_").await;
    let bob_tablet = second_device(&p.app, p.ip, &p.bob).await;
    close_ws(connect_capable(p.addr, &p.state, &p.bob).await).await;
    close_ws(connect_capable(p.addr, &p.state, &bob_tablet).await).await;
    wait_online(&p.state, &p.bob, false).await;
    wait_online(&p.state, &bob_tablet, false).await;

    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(created["peer_ringing_expected"], true);
    assert_eq!(created["peer_ringing"], false, "nobody confirmed yet");
    let id = call_id(&created);
    assert_eq!(bits(&p.state.db, &id).await, (true, false));
    let recovered = get(&p.app, p.ip, &p.alice, "/calls/active").await;
    assert_eq!(recovered["id"], id.as_str());
    assert_eq!(recovered["peer_ringing_expected"], true);

    // The callee never sees either field, on any device.
    let callee_view = get(&p.app, p.ip, &p.bob, &format!("/calls/{id}")).await;
    assert!(!has_ringing_fields(&callee_view), "{callee_view}");
    let callee_active = get(&p.app, p.ip, &bob_tablet, "/calls/active").await;
    assert!(!has_ringing_fields(&callee_active), "{callee_active}");
}

#[tokio::test]
async fn expectation_false_when_one_callee_device_is_an_older_build() {
    let p = pair("ring_exp_old_").await;
    let bob_tablet = second_device(&p.app, p.ip, &p.bob).await;
    let _phone = connect_capable(p.addr, &p.state, &p.bob).await;
    let _tablet = connect_legacy(p.addr, &p.state, &bob_tablet).await;
    assert!(ring_ack_flag(&p.state.db, &p.bob).await);
    assert!(!ring_ack_flag(&p.state.db, &bob_tablet).await);

    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(created["peer_ringing_expected"], false, "an old build may ring without confirming");
    let id = call_id(&created);
    assert_eq!(bits(&p.state.db, &id).await, (false, false));

    // Legacy mode still reveals a confirmation from the capable device.
    age_call(&p.state.db, &id, 3).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    let row = get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await;
    assert_eq!(row["peer_ringing_expected"], false);
    assert_eq!(row["peer_ringing"], true);
}

#[tokio::test]
async fn expectation_does_not_reveal_an_incapable_devices_socket_state() {
    // An offline legacy device still opts the account into legacy mode.
    let p = pair("ring_exp_none_").await;
    let bob_tablet = second_device(&p.app, p.ip, &p.bob).await;
    assert!(ring_ack_flag(&p.state.db, &p.bob).await);
    assert!(!ring_ack_flag(&p.state.db, &bob_tablet).await);
    sqlx::query("UPDATE devices SET last_active_at = now() - interval '365 days' WHERE id = $1")
        .bind(bob_tablet.device_uuid()).execute(&p.state.db).await.unwrap();
    let first = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(first["peer_ringing_expected"], false, "dormant builds are conservatively included");
    post_ok(&p, &p.alice, &format!("/calls/{}/cancel", call_id(&first))).await;

    // Connecting and disconnecting the same build never changes the bit.
    let tablet = connect_legacy(p.addr, &p.state, &bob_tablet).await;
    let second = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(second["peer_ringing_expected"], false);
    post_ok(&p, &p.alice, &format!("/calls/{}/cancel", call_id(&second))).await;
    close_ws(tablet).await;
    wait_online(&p.state, &bob_tablet, false).await;
    let third = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(third["peer_ringing_expected"], false);
}

#[tokio::test]
async fn fcm_token_registration_and_invalidation_do_not_change_expectation() {
    check_push_token_expectation(spawn_state_with_push().await, "fcm", "ring_exp_fcm_").await;
}

#[tokio::test]
async fn apns_voip_token_on_an_incapable_device_keeps_expectation_false() {
    let mut state = spawn_state().await;
    let transport = apns_common::FakeTransport::new();
    state.push = Arc::new(messenger_server::push::PushService::disabled()
        .with_apns(vec![apns_common::provider(transport.clone())]));
    check_push_token_expectation(state, "apns_voip", "ring_exp_apns_").await;
    assert_eq!(transport.count(), 0, "the APNs transport is a stub; no worker sends");
}

async fn check_push_token_expectation(state: messenger_server::state::AppState, provider: &str, prefix: &str) {
    let p = pair_with(state, prefix).await;
    messenger_server::calls::ringing::record_capability(&p.state.db, p.bob.device_uuid(), false).await;
    let mut token = if provider == "fcm" {
        json!({ "provider": provider, "token": format!("fcm-{}", Uuid::new_v4()) })
    } else {
        apns_common::intent(provider, Uuid::new_v4(), 1)
    };
    if provider == "apns_voip" { token["token"] = json!(Uuid::new_v4().simple().to_string()); }
    let before = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(before["peer_ringing_expected"], false, "no token, offline legacy build");
    post_ok(&p, &p.alice, &format!("/calls/{}/cancel", call_id(&before))).await;
    let (status, _) = call(&p.app, "PUT", "/devices/me/push-token", p.ip, &p.bob.token, Some(token)).await;
    assert_eq!(status, StatusCode::OK);

    // No socket, old build, and a live token: still legacy mode.
    let first = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(first["peer_ringing_expected"], false);
    post_ok(&p, &p.alice, &format!("/calls/{}/cancel", call_id(&first))).await;

    // Updated: the next socket advertises the capability, then the app goes to background.
    close_ws(connect_capable(p.addr, &p.state, &p.bob).await).await;
    wait_online(&p.state, &p.bob, false).await;
    let second = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(second["peer_ringing_expected"], true, "the advertised build changed");
    post_ok(&p, &p.alice, &format!("/calls/{}/cancel", call_id(&second))).await;

    // Downgrading changes the build mix; invalidating its token cannot change it back.
    close_ws(connect_legacy(p.addr, &p.state, &p.bob).await).await;
    wait_online(&p.state, &p.bob, false).await;
    sqlx::query("UPDATE push_tokens SET invalidated_at = now() WHERE device_id = $1")
        .bind(p.bob.device_uuid()).execute(&p.state.db).await.unwrap();
    let third = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(third["peer_ringing_expected"], false, "dead tokens do not exclude an old build");
}

// ---------------------------------------------------------------------------
// 2-3. One event, to the caller only, after the floor; idempotent
// ---------------------------------------------------------------------------

#[tokio::test]
async fn one_event_after_the_floor_to_the_callers_devices_only() {
    let p = pair("ring_event_").await;
    let alice_tablet = second_device(&p.app, p.ip, &p.alice).await;
    let bob_tablet = second_device(&p.app, p.ip, &p.bob).await;
    let mut a1 = connect_legacy(p.addr, &p.state, &p.alice).await;
    let mut a2 = connect_legacy(p.addr, &p.state, &alice_tablet).await;
    let mut b1 = connect_capable(p.addr, &p.state, &p.bob).await;
    let mut b2 = connect_capable(p.addr, &p.state, &bob_tablet).await;

    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    let id = call_id(&created);
    assert!(next_of_type(&mut b1, "call.ringing", Duration::from_secs(2)).await.is_some());
    assert!(next_of_type(&mut b2, "call.ringing", Duration::from_secs(2)).await.is_some());

    let (status, body) = confirm(&p.app, p.ip, &p.bob, &id).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body.is_null(), "same shape as POST /calls/{{id}}/read: {body}");

    let frame = next_of_type(&mut a1, PEER_RINGING, Duration::from_secs(5)).await.expect("caller gets call.peer_ringing");
    let age = call_age_seconds(&p.state.db, &id).await;
    assert!(age >= 2.5, "never before created_at + 2.5 s (arrived at {age} s)");
    assert_eq!(frame, json!({ "type": PEER_RINGING, "call_id": id }), "only type and call_id");
    // Batch 3a (server-008): ringing hints reach only the caller family that created the
    // call; another signed-in caller device gets none.
    assert!(next_of_type(&mut a2, PEER_RINGING, Duration::from_secs(2)).await.is_none(),
        "another caller device gets no ringing hint");

    // A repeat from the same device and one from another callee device: 200, no second event.
    for device in [&p.bob, &bob_tablet] {
        let (status, body) = confirm(&p.app, p.ip, device, &id).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body.is_null());
    }
    assert_eq!(count_of_type(&frames_within(&mut a1, Duration::from_millis(1500)).await, PEER_RINGING), 0);
    assert_eq!(count_of_type(&frames_within(&mut a2, Duration::from_millis(200)).await, PEER_RINGING), 0);
    // The callee's own devices never get it.
    assert_eq!(count_of_type(&frames_within(&mut b1, Duration::from_millis(200)).await, PEER_RINGING), 0);
    assert_eq!(count_of_type(&frames_within(&mut b2, Duration::from_millis(200)).await, PEER_RINGING), 0);

    let row = get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await;
    assert_eq!(row["peer_ringing"], true);
    assert_eq!(bits(&p.state.db, &id).await, (true, true));
}

#[tokio::test]
async fn the_floor_counts_from_call_ringing_even_when_create_waited_on_a_lock() {
    let p = pair("ring_floor_lock_").await;
    let mut a = connect_legacy(p.addr, &p.state, &p.alice).await;
    let mut b = connect_capable(p.addr, &p.state, &p.bob).await;
    // When call.ringing actually reaches the callee.
    let ringing = tokio::spawn(async move {
        let frame = next_of_type(&mut b, "call.ringing", Duration::from_secs(10)).await;
        (frame, tokio::time::Instant::now())
    });

    // A long write (clearing a large chat, say) holds the callee's account row,
    // so create_call waits inside its transaction for longer than the floor.
    let mut holder = p.state.db.begin().await.unwrap();
    sqlx::query("SELECT id FROM users WHERE id = $1 FOR UPDATE")
        .bind(p.bob.uuid()).execute(&mut *holder).await.unwrap();
    let create = tokio::spawn({
        let (app, ip, alice, bob) = (p.app.clone(), p.ip, p.alice.clone(), p.bob.clone());
        async move { create_call(&app, ip, &alice, &bob).await }
    });
    tokio::time::sleep(Duration::from_secs(3)).await;
    assert!(!create.is_finished(), "fixture: create waits on the callee's row");
    holder.commit().await.unwrap();
    let id = call_id(&create.await.unwrap());
    let (frame, rang) = ringing.await.unwrap();
    assert_eq!(frame.expect("the callee gets call.ringing")["call_id"], id.as_str());
    let age = call_age_seconds(&p.state.db, &id).await;
    assert!(age < 1.0, "created_at is written after the wait ({age} s)");

    // The callee's app is open and confirms at once.
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    let row = get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await;
    assert_eq!(row["peer_ringing"], false, "the lock wait does not count toward the floor");
    next_of_type(&mut a, PEER_RINGING, Duration::from_secs(5)).await.expect("caller gets call.peer_ringing");
    let age = call_age_seconds(&p.state.db, &id).await;
    assert!(age >= 2.5, "never before created_at + 2.5 s (arrived at {age} s)");
    // Only the server's own commit and fan-out lie between created_at and the
    // frame; before the fix the event came at once.
    let after = rang.elapsed();
    assert!(after >= Duration::from_secs(2), "the floor runs after call.ringing ({after:?})");
}

#[tokio::test]
async fn a_slow_create_response_read_does_not_consume_the_ringing_floor() {
    let p = pair("ring_read_wait_").await;
    let mut caller = connect_legacy(p.addr, &p.state, &p.alice).await;
    let mut callee = connect_capable(p.addr, &p.state, &p.bob).await;
    let ringing = tokio::spawn(async move {
        let frame = next_of_type(&mut callee, "call.ringing", Duration::from_secs(10)).await;
        (frame, tokio::time::Instant::now())
    });
    let mut holder = p.state.db.begin().await.unwrap();
    sqlx::query("LOCK TABLE call_reads IN ACCESS EXCLUSIVE MODE").execute(&mut *holder).await.unwrap();
    let create = tokio::spawn({
        let (app, ip, alice, bob) = (p.app.clone(), p.ip, p.alice.clone(), p.bob.clone());
        async move { create_call(&app, ip, &alice, &bob).await }
    });
    tokio::time::sleep(Duration::from_secs(3)).await;
    assert!(!create.is_finished(), "fixture: the response read is blocked");
    holder.rollback().await.unwrap();
    let id = call_id(&create.await.unwrap());
    let (frame, rang) = ringing.await.unwrap();
    assert_eq!(frame.unwrap()["call_id"], id);
    assert!(call_age_seconds(&p.state.db, &id).await < 1.0, "anchor follows the response read");
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    assert_eq!(get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await["peer_ringing"], false);
    next_of_type(&mut caller, PEER_RINGING, Duration::from_secs(5)).await.unwrap();
    assert!(rang.elapsed() >= Duration::from_secs(2), "the read wait did not use the floor");
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
}

#[tokio::test]
async fn create_response_read_failure_rolls_back_before_notifying_the_callee() {
    let p = pair("ring_create_read_").await;
    let mut callee = connect_capable(p.addr, &p.state, &p.bob).await;
    // A separate, bounded pool makes the response SELECT fail deterministically
    // without killing a connection or disturbing other requests' timeouts.
    let db = sqlx::postgres::PgPoolOptions::new().max_connections(2)
        .after_connect(|conn, _| Box::pin(async move {
            sqlx::query("SET statement_timeout = '500ms'").execute(conn).await?;
            Ok(())
        })).connect(&p.state.config.database_url).await.unwrap();
    let mut failing_state = p.state.clone();
    failing_state.db = db.clone();
    let failing_app = messenger_server::app(failing_state);
    let mut holder = p.state.db.begin().await.unwrap();
    // This table is needed only by the response view, after the inserts. The
    // relation lock makes that SELECT time out, even if its unread CASE is false.
    sqlx::query("LOCK TABLE call_reads IN ACCESS EXCLUSIVE MODE").execute(&mut *holder).await.unwrap();
    let (status, _) = try_create_call(&failing_app, p.ip, &p.alice, &p.bob).await;
    holder.rollback().await.unwrap();
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
    let calls: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM calls WHERE caller_user_id = $1")
        .bind(p.alice.uuid()).fetch_one(&p.state.db).await.unwrap();
    let slots: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM call_participants_active WHERE user_id = ANY($1)")
        .bind(vec![p.alice.uuid(), p.bob.uuid()]).fetch_one(&p.state.db).await.unwrap();
    assert_eq!((calls, slots), (0, 0), "response failure rolls back the whole create");
    assert!(next_of_type(&mut callee, "call.ringing", Duration::from_millis(300)).await.is_none());
    // Both users can immediately place a fresh call after the failed request.
    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(created["chat_visible"], true);
    assert_eq!(created["peer_ringing_expected"], true);
    assert_eq!(created["peer_ringing"], false);
    let id = call_id(&created);
    assert_eq!(next_of_type(&mut callee, "call.ringing", Duration::from_secs(2)).await.unwrap()["call_id"], id);
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
    db.close().await;
}

#[tokio::test]
async fn concurrent_confirmations_from_two_devices_produce_one_event() {
    let p = pair("ring_concurrent_").await;
    let bob_tablet = second_device(&p.app, p.ip, &p.bob).await;
    let mut a = connect_legacy(p.addr, &p.state, &p.alice).await;
    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    let id = call_id(&created);
    age_call(&p.state.db, &id, 5).await;

    let (first, second) = tokio::join!(confirm(&p.app, p.ip, &p.bob, &id), confirm(&p.app, p.ip, &bob_tablet, &id));
    assert_eq!((first.0, second.0), (StatusCode::OK, StatusCode::OK));
    let frames = frames_within(&mut a, Duration::from_millis(1500)).await;
    assert_eq!(count_of_type(&frames, PEER_RINGING), 1, "{frames:?}");
}

// ---------------------------------------------------------------------------
// 4. Who may confirm
// ---------------------------------------------------------------------------

#[tokio::test]
async fn only_the_callee_from_a_device_that_may_receive_the_call_can_confirm() {
    let p = pair("ring_who_").await;
    let carol = register_user(&p.app, p.ip, "ring_who_c_").await;
    let mallory = register_user(&p.app, p.ip, "ring_who_m_").await;
    let bob_tablet = second_device(&p.app, p.ip, &p.bob).await;

    // Bob's tablet is an installation Alice blocked through another account on it.
    let installation: Uuid = sqlx::query_scalar("INSERT INTO verified_installations (credential_hash) VALUES ($1) RETURNING id")
        .bind(rand::random::<[u8; 32]>().to_vec()).fetch_one(&p.state.db).await.unwrap();
    sqlx::query("INSERT INTO verified_installation_users (installation_id, user_id) VALUES ($1, $2)")
        .bind(installation).bind(mallory.uuid()).execute(&p.state.db).await.unwrap();
    sqlx::query("UPDATE devices SET verified_installation_id = $1 WHERE id = $2")
        .bind(installation).bind(bob_tablet.device_uuid()).execute(&p.state.db).await.unwrap();
    sqlx::query("INSERT INTO user_blocks (user_id, blocked_user_id) VALUES ($1, $2)")
        .bind(p.alice.uuid()).bind(mallory.uuid()).execute(&p.state.db).await.unwrap();
    let blocked: bool = sqlx::query_scalar("SELECT user_device_blocked($1, $2)")
        .bind(bob_tablet.device_uuid()).bind(p.alice.uuid()).fetch_one(&p.state.db).await.unwrap();
    assert!(blocked, "fixture: the tablet is installation-blocked for Alice");

    let _phone = connect_capable(p.addr, &p.state, &p.bob).await;
    let mut tablet = connect_legacy(p.addr, &p.state, &bob_tablet).await;
    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(created["peer_ringing_expected"], true, "a blocked device never rings, so it does not count");
    let id = call_id(&created);
    assert_eq!(count_of_type(&frames_within(&mut tablet, Duration::from_millis(500)).await, "call.ringing"), 0);

    for (who, user) in [("caller", &p.alice), ("outsider", &carol), ("installation-blocked callee device", &bob_tablet)] {
        let (status, body) = confirm(&p.app, p.ip, user, &id).await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{who}: {body}");
        assert_no_confirmation_key(&p, user, &id).await;
    }
    let (status, _) = confirm(&p.app, p.ip, &p.bob, &Uuid::new_v4().to_string()).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "no such call");
    assert_eq!(bits(&p.state.db, &id).await, (true, false), "none of them set the bit");

    // A read-only callee cannot answer, so "ringing" would mislead.
    sqlx::query("INSERT INTO admin_user_controls (user_id, read_only_reason) VALUES ($1, 'isolated fixture')")
        .bind(p.bob.uuid()).execute(&p.state.db).await.unwrap();
    let (status, body) = confirm(&p.app, p.ip, &p.bob, &id).await;
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(body["error"]["code"], "account_read_only");
    assert_eq!(bits(&p.state.db, &id).await, (true, false));
}

#[tokio::test]
async fn a_revoked_callee_devices_token_gets_401_and_its_build_is_ignored() {
    let p = pair("ring_revoked_").await;
    let tablet = second_device(&p.app, p.ip, &p.bob).await;
    assert!(!ring_ack_flag(&p.state.db, &tablet).await);
    let (status, _) = call(&p.app, "DELETE", &format!("/devices/{}", tablet.device), p.ip, &p.bob.token, None).await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(created["peer_ringing_expected"], true, "a revoked old build does not count");
    let id = call_id(&created);
    let (status, body) = confirm(&p.app, p.ip, &tablet, &id).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["error"]["code"], "session_revoked");
    assert_eq!(bits(&p.state.db, &id).await, (true, false));
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
}

#[tokio::test]
async fn an_installation_blocked_caller_device_receives_no_peer_ringing() {
    let p = pair("ring_fanout_").await;
    let tablet = second_device(&p.app, p.ip, &p.alice).await;
    let alias = register_user(&p.app, p.ip, "ring_fanout_m_").await;
    let installation: Uuid = sqlx::query_scalar("INSERT INTO verified_installations (credential_hash) VALUES ($1) RETURNING id")
        .bind(rand::random::<[u8; 32]>().to_vec()).fetch_one(&p.state.db).await.unwrap();
    sqlx::query("INSERT INTO verified_installation_users (installation_id, user_id) VALUES ($1, $2)")
        .bind(installation).bind(alias.uuid()).execute(&p.state.db).await.unwrap();
    sqlx::query("UPDATE devices SET verified_installation_id = $1 WHERE id = $2")
        .bind(installation).bind(tablet.device_uuid()).execute(&p.state.db).await.unwrap();
    sqlx::query("INSERT INTO user_blocks (user_id, blocked_user_id) VALUES ($1, $2)")
        .bind(p.bob.uuid()).bind(alias.uuid()).execute(&p.state.db).await.unwrap();
    let blocked: bool = sqlx::query_scalar("SELECT user_device_blocked($1, $2)")
        .bind(tablet.device_uuid()).bind(p.bob.uuid()).fetch_one(&p.state.db).await.unwrap();
    assert!(blocked, "fixture: Bob blocked an alias on Alice's tablet");

    let mut phone = connect_legacy(p.addr, &p.state, &p.alice).await;
    let mut tablet_ws = connect_legacy(p.addr, &p.state, &tablet).await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    age_call(&p.state.db, &id, 3).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    let event = next_of_type(&mut phone, PEER_RINGING, Duration::from_secs(2)).await.expect("clean caller device receives the event");
    assert_eq!(event["call_id"], id.as_str());
    assert_eq!(count_of_type(&frames_within(&mut tablet_ws, Duration::from_millis(500)).await, PEER_RINGING), 0);
}

// ---------------------------------------------------------------------------
// 5. After the call left ringing
// ---------------------------------------------------------------------------

#[tokio::test]
async fn confirmation_after_the_call_left_ringing_is_409_without_an_event() {
    let p = pair("ring_late_").await;
    let mut a = connect_legacy(p.addr, &p.state, &p.alice).await;
    let mut ended = Vec::new();

    // Accepted.
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    post_ok(&p, &p.bob, &format!("/calls/{id}/accept")).await;
    let (status, body) = confirm(&p.app, p.ip, &p.bob, &id).await;
    assert_eq!(status, StatusCode::CONFLICT, "after accept: {body}");
    assert_eq!(body["error"]["code"], "conflict");
    post_ok(&p, &p.alice, &format!("/calls/{id}/hangup")).await;
    ended.push(id);

    // Declined.
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    post_ok(&p, &p.bob, &format!("/calls/{id}/reject")).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::CONFLICT, "after reject");
    ended.push(id);

    // Cancelled (also: the caller ended it locally before the confirmation).
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::CONFLICT, "after cancel");
    ended.push(id);

    // Ringing timeout.
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    sqlx::query("UPDATE calls SET ringing_deadline = now() - interval '1 second' WHERE id = $1")
        .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
    messenger_server::calls::handlers::sweep_expired_calls(&p.state).await;
    assert_eq!(get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await["end_reason"], "missed");
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::CONFLICT, "after the missed sweep");
    ended.push(id);

    let frames = frames_within(&mut a, Duration::from_secs(3)).await;
    assert_eq!(count_of_type(&frames, PEER_RINGING), 0, "{frames:?}");
    for id in &ended {
        assert_eq!(bits(&p.state.db, id).await, (false, false));
    }
}

// ---------------------------------------------------------------------------
// 6. Recovery reads
// ---------------------------------------------------------------------------

#[tokio::test]
async fn recovery_reads_carry_the_bit_only_after_the_floor() {
    let p = pair("ring_recover_").await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);

    for uri in ["/calls/active".to_string(), format!("/calls/{id}")] {
        let row = get(&p.app, p.ip, &p.alice, &uri).await;
        assert_eq!(row["peer_ringing_expected"], true, "{uri}");
        assert_eq!(row["peer_ringing"], false, "{uri}: not before created_at + 2.5 s");
    }
    age_call(&p.state.db, &id, 3).await;
    for uri in ["/calls/active".to_string(), format!("/calls/{id}")] {
        let row = get(&p.app, p.ip, &p.alice, &uri).await;
        assert_eq!(row["peer_ringing"], true, "{uri}: a relaunched or reconnected caller recovers the bit");
        let callee = get(&p.app, p.ip, &p.bob, &uri).await;
        assert!(!has_ringing_fields(&callee), "{uri} for the callee: {callee}");
    }

    let accepted = post_ok(&p, &p.bob, &format!("/calls/{id}/accept")).await;
    assert!(!has_ringing_fields(&accepted));
    let row = get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await;
    assert_eq!(row["state"], "accepted");
    assert!(!has_ringing_fields(&row), "omitted once the call left ringing: {row}");
    let hung_up = post_ok(&p, &p.alice, &format!("/calls/{id}/hangup")).await;
    assert!(!has_ringing_fields(&hung_up));
}

// ---------------------------------------------------------------------------
// 7. Bits exist only while ringing
// ---------------------------------------------------------------------------

#[tokio::test]
async fn bits_are_cleared_whenever_the_call_leaves_ringing() {
    let p = pair("ring_clear_").await;
    let ringing_call = |p: &Pair| {
        let (app, ip, alice, bob, db) = (p.app.clone(), p.ip, p.alice.clone(), p.bob.clone(), p.state.db.clone());
        async move {
            let id = call_id(&create_call(&app, ip, &alice, &bob).await);
            assert_eq!(confirm(&app, ip, &bob, &id).await.0, StatusCode::OK);
            assert_eq!(bits(&db, &id).await, (true, true), "fixture: both bits set while ringing");
            id
        }
    };
    let mut ended = Vec::new();

    let id = ringing_call(&p).await;
    post_ok(&p, &p.bob, &format!("/calls/{id}/accept")).await;
    assert_eq!(bits(&p.state.db, &id).await, (false, false), "accept");
    assert_no_confirmation_key(&p, &p.bob, &id).await;
    post_ok(&p, &p.alice, &format!("/calls/{id}/hangup")).await;
    ended.push(id);

    for (actor, action) in [(&p.bob, "reject"), (&p.alice, "cancel"), (&p.alice, "hangup"), (&p.alice, "failed")] {
        let id = ringing_call(&p).await;
        post_ok(&p, actor, &format!("/calls/{id}/{action}")).await;
        assert_eq!(bits(&p.state.db, &id).await, (false, false), "{action}");
        assert_no_confirmation_key(&p, &p.bob, &id).await;
        ended.push(id);
    }

    let id = ringing_call(&p).await;
    sqlx::query("UPDATE calls SET ringing_deadline = now() - interval '1 second' WHERE id = $1")
        .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
    messenger_server::calls::handlers::sweep_expired_calls(&p.state).await;
    assert_eq!(bits(&p.state.db, &id).await, (false, false), "missed sweep");
    assert_no_confirmation_key(&p, &p.bob, &id).await;
    ended.push(id);

    // Account-wide revoke (admin suspension, account recovery) writes the state directly.
    let id = ringing_call(&p).await;
    sqlx::query("UPDATE calls SET state='ended', end_reason='device_revoked', ended_at=now() WHERE id=$1 AND state<>'ended'")
        .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
    sqlx::query("DELETE FROM call_participants_active WHERE call_id = $1")
        .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
    assert_eq!(bits(&p.state.db, &id).await, (false, false), "account-wide revoke");
    ended.push(id);

    // The callee blocks the caller mid-ring.
    let id = ringing_call(&p).await;
    post_ok(&p, &p.bob, &format!("/users/{}/block", p.alice.id)).await;
    let reason: Option<String> = sqlx::query_scalar("SELECT end_reason FROM calls WHERE id = $1")
        .bind(Uuid::parse_str(&id).unwrap()).fetch_one(&p.state.db).await.unwrap();
    assert_eq!(reason.as_deref(), Some("cancelled"));
    assert_eq!(bits(&p.state.db, &id).await, (false, false), "block");
    assert_no_confirmation_key(&p, &p.bob, &id).await;
    ended.push(id);

    for column in ["callee_ringing", "callee_ack_expected"] {
        let error = sqlx::query(&format!("UPDATE calls SET {column} = true WHERE id = $1"))
            .bind(Uuid::parse_str(&ended[0]).unwrap()).execute(&p.state.db).await.unwrap_err();
        assert!(error.to_string().contains("calls_ringing_bits_only_while_ringing"), "{column}: {error}");
    }
}

// ---------------------------------------------------------------------------
// 8. Presence privacy gate
// ---------------------------------------------------------------------------

#[tokio::test]
async fn a_callee_hidden_from_the_caller_looks_like_an_old_client() {
    let p = pair("ring_presence_").await;
    let mut a = connect_legacy(p.addr, &p.state, &p.alice).await;
    let _b = connect_capable(p.addr, &p.state, &p.bob).await;

    // Online hidden from everyone.
    set_online_visibility(&p.state.db, &p.bob, "nobody", &[]).await;
    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(created["peer_ringing_expected"], false);
    let id = call_id(&created);
    age_call(&p.state.db, &id, 3).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    assert_eq!(count_of_type(&frames_within(&mut a, Duration::from_secs(1)).await, PEER_RINGING), 0);
    let row = get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await;
    assert_eq!((row["peer_ringing_expected"].clone(), row["peer_ringing"].clone()), (json!(false), json!(false)));
    // Shown again mid-ring: the next read reveals the bit (the frozen expectation stays false).
    set_online_visibility(&p.state.db, &p.bob, "everyone", &[]).await;
    let row = get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await;
    assert_eq!((row["peer_ringing_expected"].clone(), row["peer_ringing"].clone()), (json!(false), json!(true)));
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;

    // Hidden from this caller only.
    set_online_visibility(&p.state.db, &p.bob, "everyone", &[p.alice.uuid()]).await;
    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(created["peer_ringing_expected"], false);
    post_ok(&p, &p.alice, &format!("/calls/{}/cancel", call_id(&created))).await;

    // Visible at create, hidden mid-ring: the event re-check suppresses it and
    // the next read drops the expectation.
    set_online_visibility(&p.state.db, &p.bob, "everyone", &[]).await;
    let created = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(created["peer_ringing_expected"], true);
    let id = call_id(&created);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    set_online_visibility(&p.state.db, &p.bob, "nobody", &[]).await;
    assert_eq!(count_of_type(&frames_within(&mut a, Duration::from_secs(3)).await, PEER_RINGING), 0);
    let row = get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await;
    assert_eq!((row["peer_ringing_expected"].clone(), row["peer_ringing"].clone()), (json!(false), json!(false)));
}

// ---------------------------------------------------------------------------
// 9. Accept inside the floor
// ---------------------------------------------------------------------------

#[tokio::test]
async fn accept_inside_the_floor_sends_no_event() {
    let p = pair("ring_floor_accept_").await;
    let mut a = connect_legacy(p.addr, &p.state, &p.alice).await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    post_ok(&p, &p.bob, &format!("/calls/{id}/accept")).await;
    let frames = frames_within(&mut a, Duration::from_millis(3500)).await;
    assert_eq!(count_of_type(&frames, "call.accepted"), 1, "{frames:?}");
    assert_eq!(count_of_type(&frames, PEER_RINGING), 0, "{frames:?}");
    assert_eq!(bits(&p.state.db, &id).await, (false, false));
}

#[tokio::test]
async fn concurrent_confirmation_and_accept_or_cancel_clear_bits_without_duplicate_events() {
    let p = pair("ring_terminal_race_").await;
    let mut caller = connect_legacy(p.addr, &p.state, &p.alice).await;
    for action in ["accept", "cancel"] {
        for after_floor in [false, true] {
            let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
            if after_floor { age_call(&p.state.db, &id, 3).await; }
            let actor = if action == "accept" { &p.bob } else { &p.alice };
            let uri = format!("/calls/{id}/{action}");
            let (confirmation, transition) = tokio::join!(
                confirm(&p.app, p.ip, &p.bob, &id),
                call(&p.app, "POST", &uri, p.ip, &actor.token, None),
            );
            assert_eq!(transition.0, StatusCode::OK, "{action}: {:?}", transition.1);
            assert!(matches!(confirmation.0, StatusCode::OK | StatusCode::CONFLICT), "{action}: {confirmation:?}");
            if confirmation.0 == StatusCode::CONFLICT {
                assert_eq!(confirmation.1["error"]["code"], "conflict");
            }
            assert_eq!(bits(&p.state.db, &id).await, (false, false), "{action}");
            assert_no_confirmation_key(&p, &p.bob, &id).await;
            // The transition response reads the row even when aging the fixture
            // put created_at before this session's history boundary.
            let row = &transition.1;
            assert_eq!(row["state"], if action == "accept" { "accepted" } else { "ended" });
            if action == "cancel" { assert_eq!(row["end_reason"], "cancelled"); }
            assert!(!has_ringing_fields(row));
            let window = if after_floor { Duration::from_millis(500) } else { Duration::from_secs(3) };
            let frames = frames_within(&mut caller, window).await;
            let confirmations = frames.iter().filter(|frame| frame["type"] == PEER_RINGING && frame["call_id"] == id.as_str()).count();
            assert!(confirmations <= 1, "{action}: {frames:?}");
            if !after_floor || confirmation.0 == StatusCode::CONFLICT {
                assert_eq!(confirmations, 0, "{action}: {frames:?}");
            }
            if action == "accept" { post_ok(&p, &p.alice, &format!("/calls/{id}/hangup")).await; }
        }
    }
}

// ---------------------------------------------------------------------------
// 10. Capability advertisement
// ---------------------------------------------------------------------------

#[tokio::test]
async fn the_capability_flag_follows_the_latest_socket_and_writes_only_on_change() {
    let mut p = pair("ring_cap_").await;
    // Use a new device rather than the default capable fixture.
    p.bob = second_device(&p.app, p.ip, &p.bob).await;
    let db = &p.state.db;
    assert!(!ring_ack_flag(db, &p.bob).await, "new devices start without the capability");

    // Written before the upgrade response, so it is current once connected.
    let first = connect_ws(p.addr, &p.bob, Some("call-ring-ack")).await;
    assert!(ring_ack_flag(db, &p.bob).await);
    // Let the connect's own last-active touch land before reading xmin.
    for _ in 0..200 {
        let touched: bool = sqlx::query_scalar("SELECT last_active_at IS NOT NULL FROM devices WHERE id = $1")
            .bind(p.bob.device_uuid()).fetch_one(db).await.unwrap();
        if touched { break; }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    tokio::time::sleep(Duration::from_millis(200)).await;
    let xmin = |db: sqlx::PgPool, device: Uuid| async move {
        sqlx::query_scalar::<_, String>("SELECT xmin::text FROM devices WHERE id = $1").bind(device).fetch_one(&db).await.unwrap()
    };
    let before = xmin(db.clone(), p.bob.device_uuid()).await;
    let repeat = connect_ws(p.addr, &p.bob, Some("future-thing, call-ring-ack")).await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(ring_ack_flag(db, &p.bob).await);
    assert_eq!(xmin(db.clone(), p.bob.device_uuid()).await, before, "an unchanged flag is not rewritten");
    close_ws(first).await;
    close_ws(repeat).await;

    // An older build (no header), a wrong case or unknown tokens only: cleared.
    for header in [None, Some("Call-Ring-Ack"), Some("something-else")] {
        close_ws(connect_ws(p.addr, &p.bob, Some("call-ring-ack")).await).await;
        assert!(ring_ack_flag(db, &p.bob).await);
        close_ws(connect_ws(p.addr, &p.bob, header).await).await;
        assert!(!ring_ack_flag(db, &p.bob).await, "{header:?}");
    }
}

// ---------------------------------------------------------------------------
// 11. Rate limits
// ---------------------------------------------------------------------------

#[tokio::test]
async fn pair_and_confirmation_rate_limits() {
    let p = pair("ring_limits_").await;
    let carol = register_user(&p.app, p.ip, "ring_limits_c_").await;
    create_direct_chat(&p.app, p.ip, &p.alice, &carol).await;

    let first = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    for attempt in 2..=10 {
        let (status, body) = try_create_call(&p.app, p.ip, &p.alice, &p.bob).await;
        assert_eq!(status, StatusCode::CONFLICT, "attempt {attempt} is busy, and still counted: {body}");
        assert_eq!(body["error"]["message"], "busy");
    }
    let (status, _) = try_create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "11th call to the same person within 10 minutes");
    post_ok(&p, &p.alice, &format!("/calls/{}/cancel", call_id(&first))).await;
    // Another callee is unaffected.
    let other = create_call(&p.app, p.ip, &p.alice, &carol).await;
    let id = call_id(&other);
    let carol_tablet = second_device(&p.app, p.ip, &carol).await;

    for attempt in 1..=10 {
        let device = if attempt % 2 == 0 { &carol_tablet } else { &carol };
        assert_eq!(confirm(&p.app, p.ip, device, &id).await.0, StatusCode::OK, "confirmation {attempt}");
    }
    for device in [&carol, &carol_tablet] {
        assert_eq!(confirm(&p.app, p.ip, device, &id).await.0, StatusCode::TOO_MANY_REQUESTS, "one call's budget is shared by the callee's devices");
    }
    // Flood rejected requests beyond the old 120/IP budget. They must never
    // stop another authenticated callee behind the same NAT from confirming.
    for _ in 0..130 {
        assert_eq!(confirm(&p.app, p.ip, &carol, &id).await.0, StatusCode::TOO_MANY_REQUESTS);
    }
    let dave = register_user(&p.app, p.ip, "ring_limits_d_").await;
    create_direct_chat(&p.app, p.ip, &dave, &p.bob).await;
    let independent = call_id(&create_call(&p.app, p.ip, &dave, &p.bob).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &independent).await.0, StatusCode::OK, "another user on the same IP retains its budget");
}

#[tokio::test]
async fn one_callers_calls_cannot_use_up_the_confirmations_for_another_callers_call() {
    let p = pair("ring_budget_").await;
    let bob_tablet = second_device(&p.app, p.ip, &p.bob).await;
    // A burst of short calls, the most the pair limit admits, each confirmed by
    // both devices with repeats and a late confirmation: 100 requests within a
    // minute, far past the old shared budget of 30.
    for round in 1..=10 {
        let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
        for attempt in 1..=9 {
            let device = if attempt % 2 == 0 { &bob_tablet } else { &p.bob };
            assert_eq!(confirm(&p.app, p.ip, device, &id).await.0, StatusCode::OK, "call {round}, confirmation {attempt}");
        }
        post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
        assert_eq!(confirm(&p.app, p.ip, &bob_tablet, &id).await.0, StatusCode::CONFLICT, "call {round}, late");
    }

    let dave = register_user(&p.app, p.ip, "ring_budget_d_").await;
    create_direct_chat(&p.app, p.ip, &dave, &p.bob).await;
    let mut caller = connect_legacy(p.addr, &p.state, &dave).await;
    let id = call_id(&create_call(&p.app, p.ip, &dave, &p.bob).await);
    age_call(&p.state.db, &id, 3).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK, "another caller's call is still confirmed");
    assert!(next_of_type(&mut caller, PEER_RINGING, Duration::from_secs(2)).await.is_some());
}

#[tokio::test]
async fn the_per_user_confirmation_backstop_bounds_sprayed_call_ids() {
    let p = pair("ring_backstop_").await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    // Seed the backstop (600 a minute) as if 599 random ids had been sent.
    let key = format!("ratelimit:calls_ringing:user:{}:0.0.0.0", p.bob.id);
    let mut redis = p.state.redis.clone();
    let _: () = redis::cmd("SET").arg(&key).arg(599).arg("EX").arg(60).query_async(&mut redis).await.unwrap();
    let (status, _) = confirm(&p.app, p.ip, &p.bob, &Uuid::new_v4().to_string()).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "the 600th request of the window is admitted");
    let (status, _) = confirm(&p.app, p.ip, &p.bob, &Uuid::new_v4().to_string()).await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::TOO_MANY_REQUESTS, "the budget covers every call of the user");
    assert_eq!(bits(&p.state.db, &id).await, (true, false));
    assert_no_confirmation_key(&p, &p.bob, &id).await;
    for _ in 0..20 {
        let random = Uuid::new_v4().to_string();
        assert_eq!(confirm(&p.app, p.ip, &p.bob, &random).await.0, StatusCode::TOO_MANY_REQUESTS);
        assert_no_confirmation_key(&p, &p.bob, &random).await;
    }
}

fn confirmation_key(callee: &User, id: &str) -> String {
    format!("ratelimit:calls_ringing:call:{}:{id}:0.0.0.0", callee.id)
}

async fn assert_no_confirmation_key(p: &Pair, callee: &User, id: &str) {
    let exists: bool = redis::cmd("EXISTS").arg(confirmation_key(callee, id))
        .query_async(&mut p.state.redis.clone()).await.unwrap();
    assert!(!exists, "no confirmation record for call {id}");
}

#[tokio::test]
async fn unknown_foreign_and_late_confirmations_create_no_per_call_keys() {
    let p = pair("ring_nokeys_").await;
    let carol = register_user(&p.app, p.ip, "ring_nokeys_c_").await;
    create_direct_chat(&p.app, p.ip, &p.alice, &carol).await;
    let bob_tablet = second_device(&p.app, p.ip, &p.bob).await;
    // Bob's own ringing call, a call between two other people, and random ids.
    let own = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    for device in [&p.bob, &bob_tablet, &p.bob] {
        assert_eq!(confirm(&p.app, p.ip, device, &own).await.0, StatusCode::OK);
    }
    let count: u64 = redis::cmd("GET").arg(confirmation_key(&p.bob, &own))
        .query_async(&mut p.state.redis.clone()).await.unwrap();
    assert_eq!(count, 3, "the callee's devices share the one per-call counter");
    post_ok(&p, &p.alice, &format!("/calls/{own}/cancel")).await;
    // Historical/terminal calls may have no ringing deadline at all.
    sqlx::query("UPDATE calls SET ringing_deadline = NULL WHERE id = $1")
        .bind(Uuid::parse_str(&own).unwrap()).execute(&p.state.db).await.unwrap();
    assert_eq!(confirm(&p.app, p.ip, &bob_tablet, &own).await.0, StatusCode::CONFLICT, "late");
    let dave = register_user(&p.app, p.ip, "ring_nokeys_d_").await;
    create_direct_chat(&p.app, p.ip, &dave, &carol).await;
    let foreign = call_id(&create_call(&p.app, p.ip, &dave, &carol).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &foreign).await.0, StatusCode::NOT_FOUND, "foreign");
    let mut sprayed = Vec::new();
    for _ in 0..20 {
        let id = Uuid::new_v4().to_string();
        assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::NOT_FOUND, "sprayed");
        sprayed.push(id);
    }

    // Invalid ids never get a key; the ended call's key was deleted, and the
    // late confirmation did not recreate it. All requests spend the backstop.
    let mut redis = p.state.redis.clone();
    for id in [own.clone(), foreign.clone()].iter().chain(&sprayed) {
        assert_no_confirmation_key(&p, &p.bob, id).await;
    }
    let keys: Vec<String> = redis::cmd("KEYS").arg(format!("ratelimit:calls_ringing:*{}*", p.bob.id)).query_async(&mut redis).await.unwrap();
    assert_eq!(keys, vec![format!("ratelimit:calls_ringing:user:{}:0.0.0.0", p.bob.id)]);
    let count: u64 = redis::cmd("GET").arg(&keys[0]).query_async(&mut redis).await.unwrap();
    assert_eq!(count, 25, "each request costs one increment, whatever the id");
}

#[tokio::test]
async fn confirmation_key_expires_with_the_remaining_ringing_window_and_is_not_extended() {
    let p = pair("ring_key_ttl_").await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    sqlx::query("UPDATE calls SET ringing_deadline = clock_timestamp() + interval '900 milliseconds' WHERE id = $1")
        .bind(Uuid::parse_str(&id).unwrap()).execute(&p.state.db).await.unwrap();
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    let key = confirmation_key(&p.bob, &id);
    let mut redis = p.state.redis.clone();
    let first: i64 = redis::cmd("PTTL").arg(&key).query_async(&mut redis).await.unwrap();
    assert!((1..=900).contains(&first), "only the remaining ringing time, not a fresh 60 s window: {first}");
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    let second: i64 = redis::cmd("PTTL").arg(&key).query_async(&mut redis).await.unwrap();
    assert!(second > 0 && second < first - 100, "a repeat must not extend expiry: {first} -> {second}");
    tokio::time::sleep(Duration::from_millis(900)).await;
    assert_no_confirmation_key(&p, &p.bob, &id).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::CONFLICT, "expired but not yet swept");
    assert_no_confirmation_key(&p, &p.bob, &id).await;
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
}

#[tokio::test]
async fn pair_limit_allows_two_bursts_across_expiry_but_user_limit_caps_them_at_twenty() {
    let p = pair("ring_boundary_").await;
    let first = create_call(&p.app, p.ip, &p.alice, &p.bob).await;
    for attempt in 2..=10 {
        assert_eq!(try_create_call(&p.app, p.ip, &p.alice, &p.bob).await.0, StatusCode::CONFLICT, "first window attempt {attempt}");
    }
    let key = format!("ratelimit:calls_create:pair:{}:{}:0.0.0.0", p.alice.id, p.bob.id);
    let mut redis = p.state.redis.clone();
    let ttl: i64 = redis::cmd("TTL").arg(&key).query_async(&mut redis).await.unwrap();
    assert!((1..=600).contains(&ttl), "fixture: the actual pair bucket has a fixed ten-minute TTL: {ttl}");
    // Move only this fixture's boundary forward rather than waiting ten minutes.
    let changed: i64 = redis::cmd("PEXPIRE").arg(&key).arg(1).query_async(&mut redis).await.unwrap();
    assert_eq!(changed, 1);
    let mut expired = false;
    for _ in 0..200 {
        let exists: bool = redis::cmd("EXISTS").arg(&key).query_async(&mut redis).await.unwrap();
        if !exists { expired = true; break; }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert!(expired, "fixture's pair window expired");
    for attempt in 11..=20 {
        assert_eq!(try_create_call(&p.app, p.ip, &p.alice, &p.bob).await.0, StatusCode::CONFLICT, "second window attempt {attempt}");
    }
    let count: i64 = redis::cmd("GET").arg(&key).query_async(&mut redis).await.unwrap();
    assert_eq!(count, 10, "the second pair window admits another ten attempts");
    assert_eq!(try_create_call(&p.app, p.ip, &p.alice, &p.bob).await.0, StatusCode::TOO_MANY_REQUESTS, "the user minute bucket caps the combined burst at twenty");
    let count_after: i64 = redis::cmd("GET").arg(&key).query_async(&mut redis).await.unwrap();
    assert_eq!(count_after, 10, "the twenty-first request is stopped by the user bucket before reaching the pair bucket");
    post_ok(&p, &p.alice, &format!("/calls/{}/cancel", call_id(&first))).await;
}

// ---------------------------------------------------------------------------
// 13. Probing stays visible to the callee
// ---------------------------------------------------------------------------

#[tokio::test]
async fn a_probe_call_leaves_an_unread_entry_for_the_callee() {
    let p = pair("ring_probe_").await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    tokio::time::sleep(Duration::from_secs(1)).await;
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
    let history = get(&p.app, p.ip, &p.bob, "/calls/history").await;
    let item = history["items"].as_array().unwrap().iter().find(|item| item["id"] == id.as_str()).expect("in the callee's history");
    assert_eq!(item["end_reason"], "cancelled");
    assert_eq!(item["unread"], true);
    assert!(!has_ringing_fields(item), "history rows never carry the ringing fields");
    let callers = get(&p.app, p.ip, &p.alice, "/calls/history").await;
    let item = callers["items"].as_array().unwrap().iter().find(|item| item["id"] == id.as_str()).expect("in the caller's history");
    assert!(!has_ringing_fields(item));
}

#[tokio::test]
async fn caller_hangup_and_blocks_during_ringing_leave_the_callee_an_unread_cancelled_entry() {
    let p = pair("ring_probe_end_").await;
    for action in ["hangup", "caller_block", "callee_block"] {
        let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
        assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
        // Keep the real creation time so history visibility remains realistic.
        tokio::time::sleep(Duration::from_millis(2600)).await;
        assert_eq!(get(&p.app, p.ip, &p.alice, &format!("/calls/{id}")).await["peer_ringing"], true);
        if action == "hangup" {
            let ended = post_ok(&p, &p.alice, &format!("/calls/{id}/hangup")).await;
            assert_eq!(ended["end_reason"], "cancelled");
        } else {
            let (actor, peer) = if action == "caller_block" { (&p.alice, &p.bob) } else { (&p.bob, &p.alice) };
            let uri = format!("/users/{}/block", peer.id);
            post_ok(&p, actor, &uri).await;
            let (status, _) = call(&p.app, "DELETE", &uri, p.ip, &actor.token, None).await;
            assert_eq!(status, StatusCode::OK, "unblock restores the history row");
        }
        assert_eq!(bits(&p.state.db, &id).await, (false, false), "{action}");
        let row = get(&p.app, p.ip, &p.bob, &format!("/calls/{id}")).await;
        assert_eq!(row["state"], "ended", "{action}");
        assert_eq!(row["end_reason"], "cancelled", "{action}");
        assert!(row["accepted_at"].is_null(), "{action}");
        assert_eq!(row["unread"], true, "{action}: direct fetch");
        let history = get(&p.app, p.ip, &p.bob, "/calls/history").await;
        let item = history["items"].as_array().unwrap().iter().find(|item| item["id"] == id.as_str()).expect("callee history retains the attempt");
        assert_eq!(item["end_reason"], "cancelled", "{action}");
        assert_eq!(item["unread"], true, "{action}: history");
        assert!(!has_ringing_fields(item));
    }
}

#[tokio::test]
async fn a_callee_hangup_before_answer_is_rejected() {
    let p = pair("ring_callee_hangup_").await;
    let mut caller = connect_legacy(p.addr, &p.state, &p.alice).await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    let ended = post_ok(&p, &p.bob, &format!("/calls/{id}/hangup")).await;
    assert_eq!(ended["state"], "ended");
    assert_eq!(ended["end_reason"], "rejected");
    assert!(ended["accepted_at"].is_null());
    assert_eq!(bits(&p.state.db, &id).await, (false, false));
    let frames = frames_within(&mut caller, Duration::from_secs(3)).await;
    assert_eq!(count_of_type(&frames, PEER_RINGING), 0, "{frames:?}");
    let ended_frames: Vec<_> = frames.iter().filter(|frame| frame["type"] == "call.ended" && frame["call_id"] == id.as_str()).collect();
    assert_eq!(ended_frames.len(), 1, "{frames:?}");
    assert_eq!(ended_frames[0]["reason"], "rejected");
}

// ---------------------------------------------------------------------------
// 14. Logs carry no new ids
// ---------------------------------------------------------------------------

static LOGS: Mutex<Vec<u8>> = Mutex::new(Vec::new());

struct Capture;

impl Write for Capture {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        LOGS.lock().unwrap().extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
}

/// Every level of the server's own logs (the generic HTTP trace layer and sqlx
/// are not this feature's). Shared by the whole binary; each test's ids are unique.
fn capture_logs() -> usize {
    static INSTALLED: OnceLock<()> = OnceLock::new();
    INSTALLED.get_or_init(|| {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(tracing_subscriber::EnvFilter::new("messenger_server=trace"))
            .with_writer(|| Capture)
            .with_ansi(false)
            .try_init();
    });
    LOGS.lock().unwrap().len()
}

#[tokio::test]
async fn the_feature_logs_no_user_device_or_call_ids() {
    capture_logs();
    let p = pair("ring_logs_").await;
    let start = capture_logs();

    let mut a = connect_legacy(p.addr, &p.state, &p.alice).await;
    let b = connect_capable(p.addr, &p.state, &p.bob).await;
    let id = call_id(&create_call(&p.app, p.ip, &p.alice, &p.bob).await);
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    assert!(next_of_type(&mut a, PEER_RINGING, Duration::from_secs(5)).await.is_some());
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::OK);
    assert_eq!(confirm(&p.app, p.ip, &p.alice, &id).await.0, StatusCode::NOT_FOUND);
    post_ok(&p, &p.alice, &format!("/calls/{id}/cancel")).await;
    assert_eq!(confirm(&p.app, p.ip, &p.bob, &id).await.0, StatusCode::CONFLICT);
    close_ws(a).await;
    close_ws(b).await;
    wait_online(&p.state, &p.alice, false).await;
    wait_online(&p.state, &p.bob, false).await;
    tokio::time::sleep(Duration::from_millis(200)).await;

    let logs = String::from_utf8_lossy(&LOGS.lock().unwrap()[start..]).into_owned();
    let ids = [&p.alice.id, &p.alice.device, &p.bob.id, &p.bob.device];
    let mut existing = 0;
    for line in logs.lines() {
        assert!(!line.contains(&id), "no log line carries the call id: {line}");
        if ids.iter().any(|value| line.contains(value.as_str())) {
            // The socket connect/disconnect lines predate this feature.
            assert!(line.contains("websocket client connected") || line.contains("websocket client disconnected"), "new id-bearing log line: {line}");
            existing += 1;
        }
    }
    assert!(existing >= 2, "the capture works: the existing socket lines are there\n{logs}");
}

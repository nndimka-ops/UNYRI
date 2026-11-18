//! `CALL_RINGING_SIGNAL=off` (docs/CALL_RINGING_SPEC.md 2.8): legacy mode for
//! every caller without a client release. Its own binary, because the switch is
//! read once per process.

mod calls_ringing_common;

use std::time::Duration;

use axum::http::StatusCode;

use calls_ringing_common::*;

#[tokio::test]
async fn kill_switch_off_is_legacy_mode_for_everyone() {
    std::env::set_var("CALL_RINGING_SIGNAL", "off");
    let state = spawn_state().await;
    let app = messenger_server::app(state.clone());
    let addr = spawn_server(state.clone()).await;
    let ip = connect_info();
    let alice = register_user(&app, ip, "ring_off_a_").await;
    let bob = register_user(&app, ip, "ring_off_b_").await;
    create_direct_chat(&app, ip, &alice, &bob).await;
    let mut a = connect_legacy(addr, &state, &alice).await;
    let _b = connect_capable(addr, &state, &bob).await;

    let created = create_call(&app, ip, &alice, &bob).await;
    assert_eq!(created["peer_ringing_expected"], false, "expected is stored false");
    assert_eq!(created["peer_ringing"], false);
    let id = call_id(&created);
    assert_eq!(bits(&state.db, &id).await, (false, false));

    // Confirmations still succeed and set the bit, which is harmless and
    // cleared after ringing; nothing reaches the caller.
    let (status, body) = confirm(&app, ip, &bob, &id).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body.is_null());
    assert_eq!(bits(&state.db, &id).await, (false, true));
    let frames = frames_within(&mut a, Duration::from_secs(3)).await;
    assert_eq!(count_of_type(&frames, "call.peer_ringing"), 0, "{frames:?}");
    let row = get(&app, ip, &alice, &format!("/calls/{id}")).await;
    assert_eq!(row["peer_ringing_expected"], false);
    assert_eq!(row["peer_ringing"], false);
}

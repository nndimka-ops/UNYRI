mod apns_common;
use apns_common::*;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use messenger_server::push::{apns::*, PushEvent};
use ring::signature::{UnparsedPublicKey, ECDSA_P256_SHA256_FIXED};
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    sync::{
        atomic::{AtomicI64, Ordering},
        Arc,
    },
};
use uuid::Uuid;

struct TestClock(AtomicI64);
impl Clock for TestClock {
    fn now_seconds(&self) -> i64 {
        self.0.load(Ordering::SeqCst)
    }
}
fn make_target(kind: Kind) -> Target {
    Target {
        kind,
        environment: Environment::Production,
        topic: if kind == Kind::Voip {
            format!("{BUNDLE}.voip")
        } else {
            BUNDLE.into()
        },
        token: "aabbccdd".into(),
        registration_id: Uuid::new_v4(),
        revision: 1,
        token_registered_at: chrono::Utc::now(),
        preferences: Preferences {
            message_alert_enabled: true,
            message_sound_enabled: true,
            permission_allowed: true,
        },
    }
}
fn context() -> Context {
    Context {
        push_id: Uuid::new_v4(),
        user_id: Uuid::new_v4(),
        device_id: Uuid::new_v4(),
        sent_at_ms: 10000,
        expires_at_ms: 20000,
    }
}

#[tokio::test]
async fn es256_jwt_verifies_reuses_and_refreshes_once_for_concurrent_sends() {
    let clock = Arc::new(TestClock(AtomicI64::new(1800000000)));
    let transport = FakeTransport::new();
    let (pem, public) = signing_fixture();
    let provider = Arc::new(
        Provider::new(
            Environment::Production,
            HashSet::from([BUNDLE.into()]),
            JwtProvider::new(
                "TESTTEAM01".into(),
                "TESTKEY001".into(),
                pem.as_bytes(),
                clock.clone(),
            )
            .unwrap(),
            transport.clone(),
        )
        .unwrap(),
    );
    let mut tasks = Vec::new();
    for _ in 0..16 {
        let p = provider.clone();
        tasks.push(tokio::spawn(async move {
            p.send(
                &make_target(Kind::Alert),
                &PushEvent::new_message(Uuid::new_v4(), Uuid::new_v4(), 1),
                &context(),
            )
            .await
            .unwrap()
        }));
    }
    for task in tasks {
        assert_eq!(task.await.unwrap(), Outcome::Accepted);
    }
    let first = transport.requests.lock().unwrap()[0].bearer.clone();
    assert!(transport
        .requests
        .lock()
        .unwrap()
        .iter()
        .all(|r| r.bearer == first));
    let parts: Vec<_> = first.split('.').collect();
    assert_eq!(parts.len(), 3);
    let header: Value = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[0]).unwrap()).unwrap();
    assert_eq!(header["alg"], "ES256");
    assert_eq!(header["kid"], "TESTKEY001");
    let claims: Value = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[1]).unwrap()).unwrap();
    assert_eq!(claims["iss"], "TESTTEAM01");
    assert_eq!(claims["iat"], 1800000000i64);
    UnparsedPublicKey::new(&ECDSA_P256_SHA256_FIXED, &public)
        .verify(
            format!("{}.{}", parts[0], parts[1]).as_bytes(),
            &URL_SAFE_NO_PAD.decode(parts[2]).unwrap(),
        )
        .unwrap();
    clock.0.store(1800002399, Ordering::SeqCst);
    provider
        .send(
            &make_target(Kind::Alert),
            &PushEvent::new_message(Uuid::new_v4(), Uuid::new_v4(), 1),
            &context(),
        )
        .await
        .unwrap();
    assert_eq!(
        transport.requests.lock().unwrap().last().unwrap().bearer,
        first
    );
    clock.0.store(1800002400, Ordering::SeqCst);
    provider
        .send(
            &make_target(Kind::Alert),
            &PushEvent::new_message(Uuid::new_v4(), Uuid::new_v4(), 1),
            &context(),
        )
        .await
        .unwrap();
    assert_ne!(
        transport.requests.lock().unwrap().last().unwrap().bearer,
        first
    );
    clock.0.store(1799999999, Ordering::SeqCst);
    assert_eq!(
        provider
            .send(
                &make_target(Kind::Alert),
                &PushEvent::new_message(Uuid::new_v4(), Uuid::new_v4(), 1),
                &context()
            )
            .await
            .unwrap_err(),
        Error::Clock
    );
}

#[test]
fn generic_payload_uses_only_allowlisted_ids_and_preserves_account_binding() {
    let target = make_target(Kind::Alert);
    let context = context();
    let notice =
        PushEvent::admin_notice(Uuid::new_v4(), "PRIVATE_TITLE", "PRIVATE_BODY", "important");
    let bytes = payload(&target, &notice, &context).unwrap();
    let text = String::from_utf8(bytes.clone()).unwrap();
    assert!(
        !text.contains("PRIVATE_TITLE")
            && !text.contains("PRIVATE_BODY")
            && !text.contains("severity")
    );
    let value: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(value["aps"]["alert"]["loc-key"], "Notifications");
    assert!(value["aps"].get("sound").is_none());
    assert_eq!(
        value["registration"]["id"],
        target.registration_id.to_string()
    );
    assert_eq!(value["user_id"], context.user_id.to_string());
    let security =
        PushEvent::security_event(42, "session_new", Uuid::new_v4(), "PRIVATE_DEVICE_NAME");
    let text = String::from_utf8(payload(&target, &security, &context).unwrap()).unwrap();
    assert!(!text.contains("PRIVATE_DEVICE_NAME") && !text.contains("subject_device"));
}

#[test]
fn message_permission_sound_and_voip_event_separation() {
    let mut target = make_target(Kind::Alert);
    let event = PushEvent::new_message(Uuid::new_v4(), Uuid::new_v4(), 1);
    let sound: Value =
        serde_json::from_slice(&payload(&target, &event, &context()).unwrap()).unwrap();
    assert_eq!(sound["aps"]["sound"], "default");
    assert_eq!(sound["aps"]["alert"]["loc-key"], "New message");
    assert!(sound["aps"].get("badge").is_none());
    target.preferences.message_sound_enabled = false;
    let quiet: Value =
        serde_json::from_slice(&payload(&target, &event, &context()).unwrap()).unwrap();
    assert!(quiet["aps"].get("sound").is_none());
    target.preferences.message_alert_enabled = false;
    assert_eq!(
        payload(&target, &event, &context()).unwrap_err(),
        Error::Payload
    );
    target.preferences.permission_allowed = false;
    assert_eq!(
        payload(
            &target,
            &PushEvent::admin_notice(Uuid::new_v4(), "a", "b", "info"),
            &context()
        )
        .unwrap_err(),
        Error::Payload
    );
    let mut voip = make_target(Kind::Voip);
    voip.preferences.permission_allowed = false;
    assert!(payload(
        &voip,
        &PushEvent::incoming_call(Uuid::new_v4(), "audio"),
        &context()
    )
    .is_ok());
    assert_eq!(
        payload(
            &voip,
            &PushEvent::call_ended(Uuid::new_v4(), "cancelled"),
            &context()
        )
        .unwrap_err(),
        Error::Payload
    );
    assert_eq!(
        payload(&voip, &event, &context()).unwrap_err(),
        Error::Payload
    );
}

#[tokio::test]
async fn fixed_endpoint_topic_type_expiry_and_invalid_tokens_fail_closed() {
    let transport = FakeTransport::new();
    let p = provider(transport.clone());
    let voip = make_target(Kind::Voip);
    p.send(
        &voip,
        &PushEvent::incoming_call(Uuid::new_v4(), "audio"),
        &context(),
    )
    .await
    .unwrap();
    {
        let rows = transport.requests.lock().unwrap();
        let request = &rows[0];
        assert_eq!(request.url, "https://api.push.apple.com/3/device/aabbccdd");
        assert_eq!(request.topic, format!("{BUNDLE}.voip"));
        assert_eq!(request.push_type, "voip");
        assert_eq!(request.expiration, 0);
    }
    let mut wrong = make_target(Kind::Alert);
    wrong.topic = "arbitrary.topic".into();
    assert!(p
        .send(
            &wrong,
            &PushEvent::new_message(Uuid::new_v4(), Uuid::new_v4(), 1),
            &context()
        )
        .await
        .is_err());
    wrong = make_target(Kind::Alert);
    wrong.environment = Environment::Sandbox;
    assert!(p
        .send(
            &wrong,
            &PushEvent::new_message(Uuid::new_v4(), Uuid::new_v4(), 1),
            &context()
        )
        .await
        .is_err());
    for token in ["", "a", "gg", "aa/bb", " aa"] {
        assert!(normalize_token(token).is_err());
    }
    assert_eq!(normalize_token("AAbb").unwrap(), "aabb");
    assert_eq!(transport.count(), 1);
}

#[test]
fn apns_errors_are_bounded_and_provider_auth_does_not_invalidate_token() {
    assert_eq!(classify(200, b""), Outcome::Accepted);
    assert_eq!(
        classify(400, br#"{"reason":"BadDeviceToken"}"#),
        Outcome::InvalidToken {
            invalid_since_ms: None
        }
    );
    assert_eq!(
        classify(410, br#"{"reason":"Unregistered","timestamp":1234}"#),
        Outcome::InvalidToken {
            invalid_since_ms: Some(1234)
        }
    );
    assert_eq!(
        classify(403, br#"{"reason":"InvalidProviderToken"}"#),
        Outcome::Permanent
    );
    assert_eq!(
        classify(400, br#"{"reason":"DeviceTokenNotForTopic"}"#),
        Outcome::Permanent
    );
    assert_eq!(
        classify(429, br#"{"reason":"TooManyProviderTokenUpdates"}"#),
        Outcome::Retry {
            minimum_delay_seconds: 60
        }
    );
    assert_eq!(
        classify(503, b""),
        Outcome::Retry {
            minimum_delay_seconds: 900
        }
    );
    assert_eq!(format!("{}", Error::Transport), "APNs Transport");
}

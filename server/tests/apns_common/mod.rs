#![allow(dead_code)]
#[path = "../push_common/mod.rs"]
pub mod legacy;
use axum::{http::StatusCode, Router};
use base64::{engine::general_purpose::STANDARD, Engine};
use messenger_server::{
    push::{apns::*, PushSendOutcome, PushService},
    state::AppState,
};
use ring::signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_FIXED_SIGNING};
use serde_json::{json, Value};
use std::{
    collections::{HashSet, VecDeque},
    sync::{Arc, Mutex},
};
use tokio::sync::Notify;
use tower::ServiceExt;
use uuid::Uuid;
pub const BUNDLE: &str = "com.example.owned-apns-test";

pub fn signing_fixture() -> (String, Vec<u8>) {
    let random = ring::rand::SystemRandom::new();
    let bytes = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, &random).unwrap();
    let key = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, bytes.as_ref(), &random)
        .unwrap();
    let encoded = STANDARD.encode(bytes.as_ref());
    let lines = encoded
        .as_bytes()
        .chunks(64)
        .map(|line| std::str::from_utf8(line).unwrap())
        .collect::<Vec<_>>()
        .join("\n");
    (
        format!("-----BEGIN PRIVATE KEY-----\n{lines}\n-----END PRIVATE KEY-----\n"),
        key.public_key().as_ref().to_vec(),
    )
}

pub struct Hold {
    pub entered: Notify,
    pub resume: Notify,
}
impl Hold {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            entered: Notify::new(),
            resume: Notify::new(),
        })
    }
}
pub struct FakeTransport {
    pub requests: Mutex<Vec<Request>>,
    pub responses: Mutex<VecDeque<Response>>,
    pub hold: Mutex<Option<Arc<Hold>>>,
}
impl FakeTransport {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            requests: Mutex::new(Vec::new()),
            responses: Mutex::new(VecDeque::new()),
            hold: Mutex::new(None),
        })
    }
    pub fn response(&self, status: u16, value: Value) {
        self.responses.lock().unwrap().push_back(Response {
            status,
            body: value.to_string().into_bytes(),
        });
    }
    pub fn count(&self) -> usize {
        self.requests.lock().unwrap().len()
    }
}
#[async_trait::async_trait]
impl Transport for FakeTransport {
    async fn post(&self, request: Request) -> Result<Response, Error> {
        self.requests.lock().unwrap().push(request);
        let hold = self.hold.lock().unwrap().clone();
        if let Some(hold) = hold {
            hold.entered.notify_one();
            hold.resume.notified().await;
        }
        Ok(self
            .responses
            .lock()
            .unwrap()
            .pop_front()
            .unwrap_or(Response {
                status: 200,
                body: Vec::new(),
            }))
    }
}

pub fn provider(transport: Arc<dyn Transport>) -> Arc<Provider> {
    let (pem, _) = signing_fixture();
    Arc::new(
        Provider::new(
            Environment::Production,
            HashSet::from([BUNDLE.into(), format!("{BUNDLE}.voip")]),
            JwtProvider::new(
                "TESTTEAM01".into(),
                "TESTKEY001".into(),
                pem.as_bytes(),
                Arc::new(SystemClock),
            )
            .unwrap(),
            transport,
        )
        .unwrap(),
    )
}

pub async fn state() -> (
    legacy::TestLock,
    AppState,
    Arc<FakeTransport>,
    Arc<legacy::FakePushProvider>,
) {
    let fcm = Arc::new(legacy::FakePushProvider::new(PushSendOutcome::Delivered));
    let (guard, mut state) = legacy::locked_state(fcm.clone()).await;
    let apns = FakeTransport::new();
    state.push = Arc::new(PushService::new(fcm.clone()).with_apns(vec![provider(apns.clone())]));
    (guard, state, apns, fcm)
}

#[derive(Clone)]
pub struct User {
    pub access: String,
    pub user: Uuid,
    pub device: Uuid,
    pub username: String,
    pub installation: Uuid,
}
pub async fn ios(app: &Router) -> User {
    let username = legacy::unique_username("apns");
    let installation = Uuid::new_v4();
    let response = app.clone().oneshot(legacy::post_json_request("/auth/register", legacy::connect_info(), "", json!({
        "username":username,"password":"correct-horse-battery","display_name":"Owned APNs fixture",
        "installation_id":installation,"device_platform":"ios","device_name":"Owned APNs fixture"
    }))).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = legacy::body_json(response).await;
    User {
        access: body["access_token"].as_str().unwrap().into(),
        user: body["user"]["id"].as_str().unwrap().parse().unwrap(),
        device: body["device_id"].as_str().unwrap().parse().unwrap(),
        username,
        installation,
    }
}

pub fn intent(provider: &str, id: Uuid, revision: i64) -> Value {
    let topic = if provider == "apns_voip" {
        format!("{BUNDLE}.voip")
    } else {
        BUNDLE.into()
    };
    json!({"provider":provider,"token":"aabbccdd","environment":"production","topic":topic,
        "registration_id":id,"revision":revision,"preferences":{"message_alert_enabled":true,"message_sound_enabled":true,"permission_allowed":true}})
}
pub async fn put(app: &Router, user: &User, body: Value) -> axum::response::Response {
    app.clone()
        .oneshot(legacy::put_json_request(
            "/devices/me/push-token",
            legacy::connect_info(),
            &user.access,
            body,
        ))
        .await
        .unwrap()
}
pub async fn delete(
    app: &Router,
    user: &User,
    provider: &str,
    id: Uuid,
    revision: i64,
) -> axum::response::Response {
    let uri = format!(
        "/devices/me/push-token?provider={provider}&registration_id={id}&revision={revision}"
    );
    app.clone()
        .oneshot(
            axum::http::Request::builder()
                .method("DELETE")
                .uri(uri)
                .header("authorization", format!("Bearer {}", user.access))
                .extension(legacy::connect_info())
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap()
}
pub async fn active(state: &AppState, user: &User, provider: &str) -> bool {
    sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM push_tokens WHERE device_id=$1 AND provider::text=$2 AND invalidated_at IS NULL AND token<>'')")
        .bind(user.device).bind(provider).fetch_one(&state.db).await.unwrap()
}

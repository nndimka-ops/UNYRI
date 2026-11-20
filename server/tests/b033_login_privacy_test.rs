//! B-033 regressions. Explicit disposable loopback DB/Redis only; no dotenv,
//! object-storage operations, live push provider, or external SFU is used.
use std::{net::SocketAddr, sync::Arc, time::Duration};

use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{Request, StatusCode},
};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use futures_util::{SinkExt, StreamExt};
use http_body_util::BodyExt;
use messenger_server::{
    auth::{jwt, middleware::AuthUser},
    config::{Config, S3Config, TurnConfig},
    state::AppState,
};
use serde_json::{json, Value};
use tokio_tungstenite::{
    tungstenite::{client::IntoClientRequest, Message},
    MaybeTlsStream, WebSocketStream,
};
use tower::ServiceExt;
use uuid::Uuid;

#[path = "common/safe_username.rs"]
mod safe_username;

async fn fixture() -> (AppState, axum::Router) {
    assert_eq!(
        std::env::var("COCO_LOGIN2_TEST_ISOLATED").as_deref(),
        Ok("1")
    );
    let database_url =
        std::env::var("TEST_DATABASE_URL").expect("explicit disposable B-033 DB required");
    let redis_url = std::env::var("TEST_REDIS_URL").expect("explicit disposable Redis required");
    for raw in [&database_url, &redis_url] {
        let url = reqwest::Url::parse(raw).unwrap();
        assert!(
            matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]")),
            "loopback fixtures only"
        );
    }
    assert_eq!(
        reqwest::Url::parse(&database_url).unwrap().path(),
        "/coco_login2_review_test"
    );
    let redis_db: u8 = reqwest::Url::parse(&redis_url)
        .unwrap()
        .path()
        .trim_start_matches('/')
        .parse()
        .expect("explicit nonzero disposable Redis index required");
    assert_eq!(redis_db, 11);
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(8)
        .connect(&database_url)
        .await
        .unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    let redis = redis::Client::open(redis_url.clone())
        .unwrap()
        .get_connection_manager()
        .await
        .unwrap();
    let config = Config {
        identity: Default::default(),
        host: "127.0.0.1".into(),
        port: 0,
        database_url,
        database_max_connections: 8,
        redis_url,
        jwt_secret: "b033-fixture-only-secret".into(),
        trusted_proxies: vec![],
        jwt_issuer: "b033-fixture".into(),
        jwt_audience: "b033-fixture-apps".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 86400,
        s3: S3Config {
            endpoint_url: "http://127.0.0.1:59000".into(),
            public_endpoint_url: "http://127.0.0.1:59000".into(),
            region: "test".into(),
            bucket: "b033-fixture".into(),
            access_key_id: "fixture".into(),
            secret_access_key: "fixture".into(),
            upload_access_key_id: "fixture".into(),
            upload_secret_access_key: "fixture".into(),
            max_upload_duration_seconds: 60,
            presign_ttl_seconds: 60,
        },
        turn: TurnConfig {
            stun_urls: vec![],
            turn_urls: vec![],
            shared_secret: "fixture".into(),
            credential_ttl_seconds: 60,
        },
        fcm: Default::default(),
    };
    let s3 = Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    let state = AppState {
        resources: Default::default(),
        db,
        redis,
        config,
        s3,
        ws_registry: Default::default(),
        push: messenger_server::push::disabled(),
    };
    let app = messenger_server::app(state.clone());
    (state, app)
}

struct Account {
    token: String,
    auth: AuthUser,
    login: String,
    public_username: Option<String>,
    number: String,
}

async fn send(
    app: &axum::Router,
    token: Option<&str>,
    method: &str,
    path: &str,
    payload: Option<Value>,
) -> (StatusCode, Value) {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    send_from(
        app,
        token,
        method,
        path,
        payload,
        [10, octets[0], octets[1], octets[2]],
    )
    .await
}

async fn send_from(
    app: &axum::Router,
    token: Option<&str>,
    method: &str,
    path: &str,
    payload: Option<Value>,
    ip: [u8; 4],
) -> (StatusCode, Value) {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .extension(ConnectInfo(SocketAddr::from((ip, 12345))));
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    let request = if let Some(payload) = payload {
        builder
            .header("content-type", "application/json")
            .body(Body::from(payload.to_string()))
            .unwrap()
    } else {
        builder.body(Body::empty()).unwrap()
    };
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let value = serde_json::from_slice(&bytes)
        .unwrap_or_else(|_| panic!("non-JSON response: {}", String::from_utf8_lossy(&bytes)));
    (status, value)
}

async fn ok(
    app: &axum::Router,
    actor: &Account,
    method: &str,
    path: &str,
    payload: Option<Value>,
) -> Value {
    let (status, value) = send(app, Some(&actor.token), method, path, payload).await;
    assert_eq!(status, StatusCode::OK, "{method} {path}: {value}");
    value
}

async fn account(state: &AppState, app: &axum::Router, public: bool) -> Account {
    let login = safe_username::unique_username("private_");
    let (status, body) = send(app, None, "POST", "/auth/register", Some(json!({
        "username": login, "password": "b033-strong-fixture-password", "display_name": "Public Person",
        "installation_id": Uuid::new_v4(), "device_platform": "android", "device_name": "B-033 fixture"
    }))).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let token = body["access_token"].as_str().unwrap().to_owned();
    let claims = jwt::decode_access_token(&state.config, &token).unwrap();
    let mut actor = Account {
        token,
        auth: AuthUser {
            user_id: claims.sub,
            device_id: claims.device_id,
            session_id: claims.session_id,
        },
        login,
        public_username: None,
        number: String::new(),
    };
    let identity = ok(app, &actor, "GET", "/users/me/identity", None).await;
    actor.number = identity["unyri_number"].as_str().unwrap().to_owned();
    assert_eq!(actor.number.len(), 9);
    assert!(actor.number.bytes().all(|digit| digit.is_ascii_digit()));
    if public {
        let handle = safe_username::unique_username("public_");
        assert_ne!(handle, actor.login);
        let value = ok(
            app,
            &actor,
            "PUT",
            "/users/me/identity",
            Some(json!({"expected_revision": identity["revision"], "public_username": handle})),
        )
        .await;
        assert_eq!(value["unyri_number"], actor.number);
        actor.public_username = Some(handle);
    }
    actor
}

fn no_private_login(value: &Value, peers: &[&Account]) {
    let serialized = value.to_string();
    for peer in peers {
        assert!(
            !serialized.contains(&peer.login),
            "private sign-in login leaked: {value}"
        );
    }
}

fn peer_identity(value: &Value, peer: &Account) {
    no_private_login(value, &[peer]);
    assert_eq!(
        value["username"].as_str(),
        Some(peer.public_username.as_deref().unwrap_or("")),
        "{value}"
    );
    assert!(
        value.get("public_username").is_some(),
        "nullable public_username must be present: {value}"
    );
    assert_eq!(value["public_username"], json!(peer.public_username));
    assert_eq!(value["unyri_number"], peer.number);
}

async fn chat(app: &axum::Router, actor: &Account, peer: &Account) -> Uuid {
    let summary = ok(
        app,
        actor,
        "POST",
        "/chats/direct",
        Some(json!({"other_user_id": peer.auth.user_id})),
    )
    .await;
    peer_identity(&summary["other_user"], peer);
    summary["chat_id"].as_str().unwrap().parse().unwrap()
}

#[tokio::test]
async fn chats_contacts_blocks_and_own_profile_keep_public_and_private_identity_separate() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, false).await;
    let named = account(&state, &app, true).await;
    let numbered = account(&state, &app, false).await;
    for peer in [&named, &numbered] {
        let id = chat(&app, &owner, peer).await;
        let summary = ok(&app, &owner, "GET", &format!("/chats/{id}"), None).await;
        peer_identity(&summary["other_user"], peer);
        ok(
            &app,
            &owner,
            "POST",
            &format!("/users/{}/contact", peer.auth.user_id),
            None,
        )
        .await;
    }
    let summaries = ok(&app, &owner, "GET", "/chats", None).await;
    let contacts = ok(&app, &owner, "GET", "/users/contacts", None).await;
    no_private_login(&summaries, &[&named, &numbered]);
    no_private_login(&contacts, &[&named, &numbered]);
    for peer in [&named, &numbered] {
        let summary = summaries["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|item| item["other_user"]["id"] == peer.auth.user_id.to_string())
            .unwrap();
        peer_identity(&summary["other_user"], peer);
        let contact = contacts["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|item| item["user"]["id"] == peer.auth.user_id.to_string())
            .unwrap();
        peer_identity(&contact["user"], peer);
        let own = ok(&app, peer, "GET", "/users/me", None).await;
        assert_eq!(
            own["username"], peer.login,
            "own profile must retain the sign-in login"
        );
        assert_eq!(own["unyri_number"], peer.number);
        assert_eq!(own["public_username"], json!(peer.public_username));
    }
    for peer in [&named, &numbered] {
        ok(
            &app,
            &owner,
            "POST",
            &format!("/users/{}/block", peer.auth.user_id),
            None,
        )
        .await;
    }
    let blocks = ok(&app, &owner, "GET", "/users/blocked", None).await;
    no_private_login(&blocks, &[&named, &numbered]);
    for peer in [&named, &numbered] {
        let row = blocks["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|item| item["user"]["id"] == peer.auth.user_id.to_string())
            .unwrap();
        peer_identity(&row["user"], peer);
        let private_match = ok(
            &app,
            &owner,
            "GET",
            &format!("/users/blocked?q={}", peer.login),
            None,
        )
        .await;
        assert!(
            private_match["items"].as_array().unwrap().is_empty(),
            "blocked filtering must not query a private login"
        );
        let public_match = ok(
            &app,
            &owner,
            "GET",
            &format!(
                "/users/blocked?q={}",
                peer.public_username.as_deref().unwrap_or(&peer.number)
            ),
            None,
        )
        .await;
        assert_eq!(public_match["items"].as_array().unwrap().len(), 1);
        peer_identity(&public_match["items"][0]["user"], peer);
    }
}

#[tokio::test]
async fn legacy_search_is_empty_and_exact_lookup_accepts_only_public_identifiers() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, false).await;
    let named = account(&state, &app, true).await;
    let numbered = account(&state, &app, false).await;
    for peer in [&named, &numbered] {
        chat(&app, &owner, peer).await;
        for endpoint in ["/users/search", "/users/directory"] {
            for query in [
                "".to_owned(),
                "a".to_owned(),
                peer.login.clone(),
                peer.login[..10].to_owned(),
                peer.number.clone(),
                format!("@{}", named.public_username.as_deref().unwrap()),
            ] {
                let page = ok(
                    &app,
                    &owner,
                    "GET",
                    &format!("{endpoint}?q={query}&limit=1&offset=0"),
                    None,
                )
                .await;
                no_private_login(&page, &[&named, &numbered]);
                assert_eq!(page["items"], json!([]));
                assert_eq!(page["next_offset"], Value::Null);
            }
            let (status, _) = send(
                &app,
                None,
                "GET",
                &format!("{endpoint}?q={}", peer.login),
                None,
            )
            .await;
            assert_eq!(status, StatusCode::UNAUTHORIZED);
        }
        let profile = ok(
            &app,
            &owner,
            "GET",
            &format!("/users/lookup?number={}", peer.number),
            None,
        )
        .await;
        no_private_login(&profile, &[&named, &numbered]);
        assert_eq!(profile["id"], peer.auth.user_id.to_string());
        assert_eq!(profile["unyri_number"], peer.number);
        assert_eq!(profile["public_username"], json!(peer.public_username));
        peer_identity(&profile, peer);
        let direct_profile = ok(
            &app,
            &owner,
            "GET",
            &format!("/users/{}/profile", peer.auth.user_id),
            None,
        )
        .await;
        no_private_login(&direct_profile, &[&named, &numbered]);
        assert_eq!(direct_profile["unyri_number"], peer.number);
        let (status, failed) = send(
            &app,
            Some(&owner.token),
            "GET",
            &format!("/users/lookup?username={}", peer.login),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{failed}");
        no_private_login(&failed, &[&named, &numbered]);
    }
    let handle = named.public_username.as_deref().unwrap();
    let found = ok(
        &app,
        &owner,
        "GET",
        &format!("/users/lookup?username=%40{handle}"),
        None,
    )
    .await;
    assert_eq!(found["id"], named.auth.user_id.to_string());
    assert_eq!(found["public_username"], handle);
    no_private_login(&found, &[&named, &numbered]);
    let (status, body) = send(
        &app,
        Some(&owner.token),
        "GET",
        &format!("/users/lookup?username={}", &handle[..handle.len() - 1]),
        None,
    )
    .await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "public username lookup must be exact: {body}"
    );
}

#[tokio::test]
async fn call_history_uses_public_username_or_empty_and_the_canonical_number() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, false).await;
    let named = account(&state, &app, true).await;
    let numbered = account(&state, &app, false).await;
    for peer in [&named, &numbered] {
        chat(&app, &owner, peer).await;
        let created = ok(
            &app,
            peer,
            "POST",
            "/calls",
            Some(json!({"callee_user_id": owner.auth.user_id, "media_kind": "audio"})),
        )
        .await;
        let id = created["id"].as_str().unwrap();
        ok(&app, peer, "POST", &format!("/calls/{id}/cancel"), None).await;
    }
    let history = ok(&app, &owner, "GET", "/calls/history", None).await;
    no_private_login(&history, &[&named, &numbered]);
    for peer in [&named, &numbered] {
        let call = history["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|item| item["peer"]["id"] == peer.auth.user_id.to_string())
            .unwrap();
        peer_identity(&call["peer"], peer);
    }
}

#[tokio::test]
async fn conference_approval_never_falls_back_to_the_private_login() {
    let (state, app) = fixture().await;
    // The approval routes need validated configuration but never contact the SFU.
    std::env::set_var("CONFERENCE_LIVEKIT_URL", "wss://127.0.0.1:17880");
    std::env::set_var("CONFERENCE_LIVEKIT_API_URL", "http://127.0.0.1:17880");
    std::env::set_var("CONFERENCE_LIVEKIT_API_KEY", "b033-fixture");
    std::env::set_var(
        "CONFERENCE_LIVEKIT_API_SECRET",
        "b033-fixture-secret-at-least-32-bytes",
    );
    std::env::set_var("CONFERENCE_LIVEKIT_AUTOCREATE_DISABLED", "true");
    std::env::set_var("CONFERENCE_GATEWAY_REQUIRED", "true");
    let owner = account(&state, &app, false).await;
    let named = account(&state, &app, true).await;
    let numbered = account(&state, &app, false).await;
    let room = Uuid::new_v4();
    sqlx::query("INSERT INTO conferences(id,owner_user_id,owner_device_id,owner_session_id,title,max_participants,invite_secret_hash,state,expires_at,listed,access_mode,approval_required,first_joined_at) VALUES($1,$2,$3,$4,'B-033 approval',10,$5,'open',now()+interval '1 hour',true,'open',true,now())")
        .bind(room).bind(owner.auth.user_id).bind(owner.auth.device_id).bind(owner.auth.session_id).bind(vec![7u8; 32]).execute(&state.db).await.unwrap();
    sqlx::query("INSERT INTO conference_members(conference_id,user_id,device_id,session_id,admission_id) VALUES($1,$2,$3,$4,$5)")
        .bind(room).bind(owner.auth.user_id).bind(owner.auth.device_id).bind(owner.auth.session_id).bind(Uuid::new_v4()).execute(&state.db).await.unwrap();
    let mut requests = Vec::new();
    for peer in [&named, &numbered] {
        // Historical blank display names used to fall back to users.username.
        sqlx::query("UPDATE users SET display_name=' ' WHERE id=$1")
            .bind(peer.auth.user_id)
            .execute(&state.db)
            .await
            .unwrap();
        let request = Uuid::new_v4();
        let entry = ok(
            &app,
            peer,
            "POST",
            &format!("/conferences/{room}/join-requests"),
            Some(json!({"request_id": request})),
        )
        .await;
        peer_identity(&entry, peer);
        assert_eq!(entry["status"], "pending");
        let read = ok(
            &app,
            peer,
            "GET",
            &format!("/conferences/{room}/join-requests/{request}"),
            None,
        )
        .await;
        peer_identity(&read, peer);
        assert_eq!(
            entry["display_name"],
            peer.public_username
                .as_ref()
                .map(|name| format!("@{name}"))
                .unwrap_or_else(|| peer.number.clone())
        );
        requests.push(request);
    }
    let queue = ok(
        &app,
        &owner,
        "GET",
        &format!("/conferences/{room}/join-requests"),
        None,
    )
    .await;
    no_private_login(&queue, &[&named, &numbered]);
    assert_eq!(queue["items"].as_array().unwrap().len(), 2);
    for (peer, request) in [&named, &numbered].into_iter().zip(requests) {
        let entry = queue["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|item| item["requester_user_id"] == peer.auth.user_id.to_string())
            .unwrap();
        peer_identity(entry, peer);
        let decided = ok(
            &app,
            &owner,
            "POST",
            &format!("/conferences/{room}/join-requests/{request}/decision"),
            Some(json!({"approve": true})),
        )
        .await;
        peer_identity(&decided, peer);
        assert_eq!(decided["status"], "approved");
    }
    // The directory and member DTOs describe peers too. Fixture an admitted
    // member so GET details exercises the production DTO without an SFU call.
    for peer in [&named, &numbered] {
        sqlx::query("INSERT INTO conference_members(conference_id,user_id,device_id,session_id,admission_id) VALUES($1,$2,$3,$4,$5)")
            .bind(room).bind(peer.auth.user_id).bind(peer.auth.device_id).bind(peer.auth.session_id).bind(Uuid::new_v4()).execute(&state.db).await.unwrap();
    }
    let detail = ok(&app, &named, "GET", &format!("/conferences/{room}"), None).await;
    no_private_login(&detail, &[&owner, &named, &numbered]);
    peer_identity(&detail, &owner);
    for peer in [&named, &numbered] {
        let participant = detail["participants"]
            .as_array()
            .unwrap()
            .iter()
            .find(|item| item["user_id"] == peer.auth.user_id.to_string())
            .unwrap();
        peer_identity(participant, peer);
    }
    sqlx::query("UPDATE users SET display_name=' ' WHERE id=$1")
        .bind(owner.auth.user_id)
        .execute(&state.db)
        .await
        .unwrap();
    let directory = ok(&app, &named, "GET", "/conferences/directory?q=B-033", None).await;
    no_private_login(&directory, &[&owner, &named, &numbered]);
    let listed = directory["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["id"] == room.to_string())
        .unwrap();
    peer_identity(listed, &owner);
    assert_eq!(listed["owner_display_name"], owner.number);
}

#[tokio::test]
async fn profile_moderation_clears_a_name_to_public_identity_without_publishing_login() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, false).await;
    let named = account(&state, &app, true).await;
    let numbered = account(&state, &app, false).await;
    // Fixture an unlocked administration session; normal factor proof and
    // handoff contracts remain covered by admin_console_test.
    sqlx::query("INSERT INTO server_administrators(user_id,role,secret,auth_policy) VALUES($1,'owner',$2,'authenticator')")
        .bind(owner.auth.user_id).bind(vec![1u8; 32]).execute(&state.db).await.unwrap();
    let admin = format!("{}abcdefghijk", Uuid::new_v4().simple());
    sqlx::query("INSERT INTO admin_sessions(token_hash,user_id,auth_session) VALUES($1,$2,$3)")
        .bind(messenger_server::admin::hash(&admin))
        .bind(owner.auth.user_id)
        .bind(owner.auth.session_id)
        .execute(&state.db)
        .await
        .unwrap();
    for peer in [&named, &numbered] {
        let request = Request::builder()
            .method("POST")
            .uri(format!(
                "/admin/users/{}/profile-moderation",
                peer.auth.user_id
            ))
            .header("authorization", format!("Bearer {}", owner.token))
            .header("x-coco-admin", &admin)
            .header("content-type", "application/json")
            .extension(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 12345))))
            .body(Body::from(
                json!({"clear_display_name": true, "reason": "B-033 privacy regression fixture"})
                    .to_string(),
            ))
            .unwrap();
        let response = app.clone().oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let summary = ok(
            &app,
            &owner,
            "POST",
            "/chats/direct",
            Some(json!({"other_user_id": peer.auth.user_id})),
        )
        .await;
        peer_identity(&summary["other_user"], peer);
        assert_eq!(
            summary["other_user"]["display_name"],
            peer.public_username
                .as_ref()
                .map(|name| format!("@{name}"))
                .unwrap_or_else(|| format!(
                    "{}\u{202f}{}\u{202f}{}",
                    &peer.number[..3],
                    &peer.number[3..6],
                    &peer.number[6..]
                ))
        );
        assert_eq!(
            ok(&app, peer, "GET", "/users/me", None).await["username"],
            peer.login
        );
    }
}

type Socket = WebSocketStream<MaybeTlsStream<tokio::net::TcpStream>>;

async fn event(socket: &mut Socket, expected: &str, peers: &[&Account]) -> Value {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let frame = socket
                .next()
                .await
                .expect("socket closed")
                .expect("websocket frame");
            if let Message::Text(text) = frame {
                let value: Value = serde_json::from_str(&text).unwrap();
                no_private_login(&value, peers);
                if value["type"] == expected {
                    return value;
                }
            }
        }
    })
    .await
    .expect("expected websocket event")
}

#[tokio::test]
async fn actual_websocket_message_call_and_presence_payloads_never_include_peer_login() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, false).await;
    let named = account(&state, &app, true).await;
    let numbered = account(&state, &app, false).await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server_app = app.clone();
    let server = tokio::spawn(async move {
        axum::serve(
            listener,
            server_app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .unwrap()
    });
    let mut request = format!("ws://{address}/ws").into_client_request().unwrap();
    request.headers_mut().insert(
        "authorization",
        format!("Bearer {}", owner.token).parse().unwrap(),
    );
    let (mut socket, _) = tokio_tungstenite::connect_async(request).await.unwrap();
    // PONG proves the real websocket handler registered before HTTP emits events.
    socket
        .send(Message::Text(
            json!({"type": "PING", "request_id": "b033"}).to_string(),
        ))
        .await
        .unwrap();
    event(&mut socket, "PONG", &[&named, &numbered]).await;
    for peer in [&named, &numbered] {
        let id = chat(&app, &owner, peer).await;
        let message = ok(&app, peer, "POST", &format!("/chats/{id}/messages"), Some(json!({
            "client_message_id": Uuid::new_v4(),
            "envelopes": [{"recipient_device_id": owner.auth.device_id, "message_type": 2, "ciphertext": STANDARD.encode(b"opaque fixture ciphertext")}]
        }))).await;
        no_private_login(&message, &[&named, &numbered]);
        let pushed = event(&mut socket, "message.new", &[&named, &numbered]).await;
        assert_eq!(
            pushed["message"]["sender_id"],
            peer.auth.user_id.to_string()
        );
        let created = ok(
            &app,
            peer,
            "POST",
            "/calls",
            Some(json!({"callee_user_id": owner.auth.user_id, "media_kind": "audio"})),
        )
        .await;
        let ring = event(&mut socket, "call.ringing", &[&named, &numbered]).await;
        assert_eq!(ring["caller_user_id"], peer.auth.user_id.to_string());
        assert_eq!(ring["call_id"], created["id"]);
        ok(
            &app,
            peer,
            "POST",
            &format!("/calls/{}/cancel", created["id"].as_str().unwrap()),
            None,
        )
        .await;
        event(&mut socket, "call.ended", &[&named, &numbered]).await;
    }
    socket.send(Message::Text(json!({"type": "presence.subscribe", "user_ids": [named.auth.user_id, numbered.auth.user_id], "lease_ms": 60000}).to_string())).await.unwrap();
    let presence = event(&mut socket, "presence.snapshot", &[&named, &numbered]).await;
    assert_eq!(presence["items"].as_array().unwrap().len(), 2);
    socket.close(None).await.unwrap();
    server.abort();
}

#[tokio::test]
async fn login_changes_are_limited_and_do_not_change_public_profile_or_emit_hints() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, false).await;
    let peer = account(&state, &app, false).await;
    let before = ok(&app, &owner, "GET", "/users/me", None).await;
    let (_, mut own_frames) = state.ws_registry.register(owner.auth.device_id).await;
    let (_, mut peer_frames) = state.ws_registry.register(peer.auth.device_id).await;
    for _ in 0..5 {
        let login = safe_username::unique_username("changed_");
        let updated = ok(
            &app,
            &owner,
            "PATCH",
            "/users/me",
            Some(json!({"username":login})),
        )
        .await;
        assert_eq!(updated["username"], login);
        assert_eq!(updated["profile_version"], before["profile_version"]);
        assert!(
            own_frames.try_recv().is_err(),
            "private edit emitted a profile hint"
        );
        assert!(
            peer_frames.try_recv().is_err(),
            "private edit reached a peer"
        );
    }
    let (status, body) = send(
        &app,
        Some(&owner.token),
        "PATCH",
        "/users/me",
        Some(json!({"username":safe_username::unique_username("limited_")})),
    )
    .await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{body}");
    // Public edits and no-op login submissions are still usable after the limit.
    let current = ok(&app, &owner, "GET", "/users/me", None).await;
    let updated = ok(
        &app,
        &owner,
        "PATCH",
        "/users/me",
        Some(json!({
            "username":current["username"], "display_name":"Changed public name"
        })),
    )
    .await;
    assert_eq!(
        updated["profile_version"].as_i64(),
        before["profile_version"].as_i64().map(|v| v + 1)
    );
    assert!(own_frames.try_recv().unwrap().contains("profile.changed"));
    assert!(peer_frames.try_recv().is_err());
    let summary = ok(
        &app,
        &peer,
        "POST",
        "/chats/direct",
        Some(json!({"other_user_id":owner.auth.user_id})),
    )
    .await;
    assert_eq!(
        summary["other_user"]["profile_version"],
        updated["profile_version"]
    );
    assert!(!summary
        .to_string()
        .contains(current["username"].as_str().unwrap()));
}

#[tokio::test]
async fn login_change_conflict_budget_is_per_account_and_keeps_honest_409_shape() {
    let (state, app) = fixture().await;
    let owner = account(&state, &app, false).await;
    let taken = account(&state, &app, false).await;
    for _ in 0..3 {
        let (status, body) = send(
            &app,
            Some(&owner.token),
            "PATCH",
            "/users/me",
            Some(json!({"username":taken.login})),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT, "{body}");
        assert_eq!(body["error"]["code"], "username_taken");
        no_private_login(&body, &[&taken]);
    }
    let (status, body) = send(
        &app,
        Some(&owner.token),
        "PATCH",
        "/users/me",
        Some(json!({"username":safe_username::unique_username("blocked_")})),
    )
    .await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{body}");
    ok(
        &app,
        &taken,
        "PATCH",
        "/users/me",
        Some(json!({"username":safe_username::unique_username("allowed_")})),
    )
    .await;
}

fn registration(login: &str, installation: Uuid) -> Value {
    json!({"username":login, "password":"b033-strong-fixture-password", "display_name":"Public Person",
        "installation_id":installation, "device_platform":"android"})
}

#[tokio::test]
async fn registration_conflicts_are_bounded_per_installation_across_networks() {
    let (state, app) = fixture().await;
    let taken = account(&state, &app, false).await;
    let installation = Uuid::new_v4();
    let (status, body) = send(
        &app,
        None,
        "POST",
        "/auth/register",
        Some(registration(
            &safe_username::unique_username("success_"),
            installation,
        )),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    // This success must refund its reservation, leaving all five honest
    // conflict responses available to the same installation below.
    let allocations: i64 = sqlx::query_scalar("SELECT count(*) FROM public_numbers")
        .fetch_one(&state.db)
        .await
        .unwrap();
    for _ in 0..5 {
        let (status, body) = send(
            &app,
            None,
            "POST",
            "/auth/register",
            Some(registration(&taken.login, installation)),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT, "{body}");
        assert_eq!(body["error"]["code"], "username_taken");
        no_private_login(&body, &[&taken]);
    }
    let (status, body) = send(
        &app,
        None,
        "POST",
        "/auth/register",
        Some(registration(
            &safe_username::unique_username("blocked_"),
            installation,
        )),
    )
    .await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{body}");
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM public_numbers")
            .fetch_one(&state.db)
            .await
            .unwrap(),
        allocations,
        "failed signups must not strand permanent number allocations"
    );
}

#[tokio::test]
async fn registration_ip_and_global_sliding_conflict_windows_apply_before_uniqueness() {
    let (state, app) = fixture().await;
    let taken = account(&state, &app, false).await;
    let ip = [198, 18, rand::random(), rand::random()];
    let ip_key = format!(
        "ratelimit:conflicts:register:ip:{}.{}.{}.{}",
        ip[0], ip[1], ip[2], ip[3]
    );
    let mut conn = state.redis.clone();
    let (seconds, micros): (i64, i64) = redis::cmd("TIME").query_async(&mut conn).await.unwrap();
    let now = seconds * 1000 + micros / 1000;
    for (key, count) in [
        (&ip_key[..], 10),
        ("ratelimit:conflicts:register:global", 100),
    ] {
        let members: Vec<String> = (0..count).map(|_| Uuid::new_v4().to_string()).collect();
        for member in &members {
            let _: usize = redis::cmd("ZADD")
                .arg(key)
                .arg(now)
                .arg(member)
                .query_async(&mut conn)
                .await
                .unwrap();
        }
        let (status, body) = send_from(
            &app,
            None,
            "POST",
            "/auth/register",
            Some(registration(&taken.login, Uuid::new_v4())),
            ip,
        )
        .await;
        // Clean only our own fixture members, including when the assertion fails.
        let _: usize = redis::cmd("ZREM")
            .arg(key)
            .arg(&members)
            .query_async(&mut conn)
            .await
            .unwrap();
        assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{key}: {body}");
    }
    // An old hit in a one-hour sliding window is removed even if the key lives.
    let stale = Uuid::new_v4().to_string();
    let _: usize = redis::cmd("ZADD")
        .arg(&ip_key)
        .arg(now - 3_600_001)
        .arg(&stale)
        .query_async(&mut conn)
        .await
        .unwrap();
    let (status, body) = send_from(
        &app,
        None,
        "POST",
        "/auth/register",
        Some(registration(&taken.login, Uuid::new_v4())),
        ip,
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    let score: Option<f64> = redis::cmd("ZSCORE")
        .arg(&ip_key)
        .arg(stale)
        .query_async(&mut conn)
        .await
        .unwrap();
    assert!(score.is_none());
}

#[tokio::test]
async fn invite_conflicts_commit_and_lock_the_invite_without_consuming_uses() {
    use sha2::{Digest, Sha256};
    let (state, app) = fixture().await;
    let taken = account(&state, &app, false).await;
    let invite = Uuid::new_v4();
    let secret = "B033INVITECONFLICTFIXTURE";
    sqlx::query("INSERT INTO registration_invites(id,code_hash,max_uses) VALUES($1,$2,10)")
        .bind(invite)
        .bind(Sha256::digest(secret.as_bytes()).to_vec())
        .execute(&state.db)
        .await
        .unwrap();
    sqlx::query("UPDATE server_policy SET registration_mode='invite_only' WHERE id=true")
        .execute(&state.db)
        .await
        .unwrap();
    let mut results = Vec::new();
    for attempt in 0..6 {
        // A fresh candidate on the last request isolates invite lockout from
        // the existing per-candidate invite-registration limiter.
        let login = if attempt < 5 {
            taken.login.clone()
        } else {
            safe_username::unique_username("unused_")
        };
        let mut payload = registration(&login, Uuid::new_v4());
        payload["invite_code"] = json!(format!("{invite}.{secret}"));
        results.push(send(&app, None, "POST", "/auth/register", Some(payload)).await);
    }
    sqlx::query("UPDATE server_policy SET registration_mode='open' WHERE id=true")
        .execute(&state.db)
        .await
        .unwrap();
    for (status, body) in &results[..5] {
        assert_eq!(*status, StatusCode::CONFLICT, "{body}");
        assert_eq!(body["error"]["code"], "username_taken");
    }
    assert_eq!(
        results[5].1["error"]["code"], "invite_invalid",
        "{:?}",
        results[5]
    );
    let (uses, conflicts, locked): (i32, i32, bool) = sqlx::query_as(
        "SELECT uses,login_conflicts,revoked_at IS NOT NULL FROM registration_invites WHERE id=$1",
    )
    .bind(invite)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!((uses, conflicts, locked), (0, 5, true));
}

#[tokio::test]
async fn conflict_reservations_bound_concurrent_decisions_and_refund_nonconflicts() {
    use messenger_server::auth::rate_limit::ConflictBudget;
    let (state, _) = fixture().await;
    let scope = format!("b033-reservation-test:{}", Uuid::new_v4());
    let scopes = [(&scope[..], 3, 60)];
    let attempts =
        futures_util::future::join_all((0..12).map(|_| ConflictBudget::reserve(&state, &scopes)))
            .await;
    assert_eq!(attempts.iter().filter(|result| result.is_ok()).count(), 3);
    for budget in attempts.into_iter().flatten() {
        budget.refund(&state).await;
    }
    let fresh = ConflictBudget::reserve(&state, &scopes)
        .await
        .expect("nonconflicts release their reservations");
    fresh.refund(&state).await;
}

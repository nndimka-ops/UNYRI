//! Real, explicitly isolated PG/Redis/S3/HTTP/WS regression tests.
use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{Request, StatusCode},
};
use futures_util::{SinkExt, StreamExt};
use http_body_util::BodyExt;
use messenger_server::{config::Config, state::AppState};
use redis::AsyncCommands;
use serde_json::{json, Value};
use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::Arc,
    time::Duration,
};
use tower::ServiceExt;
use uuid::Uuid;

async fn fixture() -> AppState {
    assert_eq!(std::env::var("COCO_SECURITY_TESTS").as_deref(), Ok("1"));
    let config = Config::from_env().unwrap();
    let db_url = reqwest::Url::parse(&config.database_url).unwrap();
    assert_eq!(
        (db_url.host_str(), db_url.port(), db_url.path()),
        (Some("127.0.0.1"), Some(55439), "/conference_test")
    );
    let redis_url = reqwest::Url::parse(&config.redis_url).unwrap();
    assert_eq!(
        (redis_url.host_str(), redis_url.port()),
        (Some("127.0.0.1"), Some(56439))
    );
    assert_eq!(config.s3.endpoint_url, "http://127.0.0.1:9000");
    assert_eq!(config.s3.public_endpoint_url, config.s3.endpoint_url);
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(8)
        .connect(&config.database_url)
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&db).await.unwrap();
    let redis =
        redis::aio::ConnectionManager::new(redis::Client::open(config.redis_url.as_str()).unwrap())
            .await
            .unwrap();
    let s3 = Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    s3.ensure_bucket().await.unwrap();
    AppState {
        resources: Default::default(),
        db,
        redis,
        config,
        ws_registry: Default::default(),
        s3,
        push: messenger_server::push::disabled(),
    }
}
fn request(method: &str, path: &str, token: Option<&str>, body: Option<Value>) -> Request<Body> {
    let octets: [u8; 3] = std::array::from_fn(|_| rand::random());
    let mut r = Request::builder()
        .method(method)
        .uri(path)
        .extension(ConnectInfo(SocketAddr::from((
            [10, octets[0], octets[1], octets[2]],
            12345,
        ))));
    if let Some(token) = token {
        r = r.header("authorization", format!("Bearer {token}"));
    }
    if let Some(body) = body {
        r.header("content-type", "application/json")
            .body(Body::from(body.to_string()))
            .unwrap()
    } else {
        r.body(Body::empty()).unwrap()
    }
}
async fn body(response: axum::response::Response) -> Value {
    serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap()
}
async fn register(app: &axum::Router) -> (String, Uuid) {
    let r=app.clone().oneshot(request("POST","/auth/register",None,Some(json!({"username":format!("sec{}",&Uuid::new_v4().simple().to_string()[..16]),"password":"security-fixture-password","display_name":"Resource regression","installation_id":Uuid::new_v4(),"device_platform":"android","device_name":"Isolated fixture"})))).await.unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    let v = body(r).await;
    (
        v["access_token"].as_str().unwrap().to_owned(),
        Uuid::parse_str(v["user"]["id"].as_str().unwrap()).unwrap(),
    )
}
fn jpeg() -> Vec<u8> {
    let mut bytes = Vec::new();
    image::codecs::jpeg::JpegEncoder::new(&mut bytes)
        .encode_image(&image::RgbImage::new(12, 8))
        .unwrap();
    bytes
}

// Fixture-only SigV4 for explicitly controlled bucket administration. Never
// accepts production endpoints; fixture() verifies the isolated namespace.
async fn fixture_storage(
    state: &AppState,
    bucket: &str,
    key: &str,
    method: &str,
    extra: &[(&str, &str)],
    bytes: Vec<u8>,
) -> reqwest::Response {
    use hmac::{Hmac, Mac};
    use sha2::{Digest, Sha256};
    fn encode(s: &str, path: bool) -> String {
        s.bytes()
            .map(|b| {
                if b.is_ascii_alphanumeric() || b"-._~".contains(&b) || (path && b == b'/') {
                    (b as char).to_string()
                } else {
                    format!("%{b:02X}")
                }
            })
            .collect()
    }
    fn mac(key: &[u8], data: &str) -> Vec<u8> {
        let mut h = Hmac::<Sha256>::new_from_slice(key).unwrap();
        h.update(data.as_bytes());
        h.finalize().into_bytes().to_vec()
    }
    assert_eq!(state.config.s3.endpoint_url, "http://127.0.0.1:9000");
    let cfg = &state.config.s3;
    let date = chrono::Utc::now();
    let day = date.format("%Y%m%d").to_string();
    let stamp = date.format("%Y%m%dT%H%M%SZ").to_string();
    let scope = format!("{day}/{}/s3/aws4_request", cfg.region);
    let uri = if key.is_empty() {
        format!("/{bucket}")
    } else {
        format!("/{bucket}/{}", encode(key, true))
    };
    let host = "127.0.0.1:9000";
    let credential = format!("{}/{scope}", cfg.access_key_id);
    let mut query = vec![
        ("X-Amz-Algorithm", "AWS4-HMAC-SHA256"),
        ("X-Amz-Credential", credential.as_str()),
        ("X-Amz-Date", stamp.as_str()),
        ("X-Amz-Expires", "60"),
        ("X-Amz-SignedHeaders", "host"),
    ];
    query.extend_from_slice(extra);
    query.sort();
    let query = query
        .into_iter()
        .map(|(k, v)| format!("{}={}", encode(k, false), encode(v, false)))
        .collect::<Vec<_>>()
        .join("&");
    let canonical = format!("{method}\n{uri}\n{query}\nhost:{host}\n\nhost\nUNSIGNED-PAYLOAD");
    let string = format!(
        "AWS4-HMAC-SHA256\n{stamp}\n{scope}\n{}",
        hex::encode(Sha256::digest(canonical.as_bytes()))
    );
    let key = mac(format!("AWS4{}", cfg.secret_access_key).as_bytes(), &day);
    let key = mac(&key, &cfg.region);
    let key = mac(&key, "s3");
    let key = mac(&key, "aws4_request");
    let signature = hex::encode(mac(&key, &string));
    reqwest::Client::new()
        .request(
            method.parse().unwrap(),
            format!("http://{host}{uri}?{query}&X-Amz-Signature={signature}"),
        )
        .body(bytes)
        .send()
        .await
        .unwrap()
}

#[tokio::test]
async fn versioned_buckets_cannot_issue_client_puts_or_release_cleanup_charges() {
    let mut state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (_, user) = register(&app).await;
    assert!(state.s3.require_client_uploads().is_ok());
    let bucket = format!("versioned-{}", Uuid::new_v4());
    let mut config = state.config.s3.clone();
    config.bucket = bucket.clone();
    state.s3 = Arc::new(messenger_server::storage::S3Client::new(config));
    state.s3.ensure_bucket().await.unwrap();
    let enabled=b"<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Status>Enabled</Status></VersioningConfiguration>".to_vec();
    assert!(
        fixture_storage(&state, &bucket, "", "PUT", &[("versioning", "")], enabled)
            .await
            .status()
            .is_success()
    );
    let key = format!("avatars/{}", Uuid::new_v4());
    let bytes = jpeg();
    for _ in 0..2 {
        assert!(
            fixture_storage(&state, &bucket, &key, "PUT", &[], bytes.clone())
                .await
                .status()
                .is_success()
        );
    }
    let listed = fixture_storage(&state, &bucket, "", "GET", &[("versions", "")], vec![])
        .await
        .text()
        .await
        .unwrap();
    assert_eq!(listed.matches("<Version>").count(), 2);
    assert!(state.s3.verify_unversioned_bucket().await.is_err());
    assert!(state
        .s3
        .presign_put_sized(&key, bytes.len() as i64)
        .is_err());
    assert!(state
        .s3
        .presign_part(&key, "fixture", 1, bytes.len() as i64)
        .is_err());
    sqlx::query("INSERT INTO avatar_uploads(object_key,user_id,expected_bytes,reserved_bytes,is_complete,upload_lease_until) VALUES($1,$2,$3,$3,true,now()-interval '1 second')").bind(&key).bind(user).bind(bytes.len() as i64).execute(&state.db).await.unwrap();
    messenger_server::users::deletion::sweep_avatar_objects(&state)
        .await
        .unwrap();
    let charged: i64 =
        sqlx::query_scalar("SELECT reserved_bytes FROM avatar_uploads WHERE object_key=$1")
            .bind(&key)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(charged, bytes.len() as i64);
    let suspended=b"<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Status>Suspended</Status></VersioningConfiguration>".to_vec();
    assert!(
        fixture_storage(&state, &bucket, "", "PUT", &[("versioning", "")], suspended)
            .await
            .status()
            .is_success()
    );
    assert!(state.s3.ensure_bucket().await.is_err());
    assert!(state.s3.require_client_uploads().is_err());
    // All versions live only in this fixture's tmpfs and are removed with it.
}
async fn avatar(app: &axum::Router, token: &str, bytes: usize) -> Value {
    let r = app
        .clone()
        .oneshot(request(
            "POST",
            "/users/me/avatar",
            Some(token),
            Some(json!({"size_bytes":bytes})),
        ))
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    body(r).await
}
async fn put(intent: &Value, bytes: Vec<u8>) -> reqwest::Response {
    reqwest::Client::new()
        .put(intent["upload_url"].as_str().unwrap())
        .body(bytes)
        .send()
        .await
        .unwrap()
}
async fn commit(app: &axum::Router, token: &str, key: &str) -> axum::response::Response {
    app.clone()
        .oneshot(request(
            "POST",
            "/users/me/avatar/complete",
            Some(token),
            Some(json!({"object_key":key})),
        ))
        .await
        .unwrap()
}

#[tokio::test]
async fn redis_windows_are_atomic_and_repair_persistent_legacy_counters() {
    let state = fixture().await;
    let bucket = format!("security-regression-{}", Uuid::new_v4());
    let ip: IpAddr = Ipv4Addr::UNSPECIFIED.into();
    let key = format!("ratelimit:{bucket}:{ip}");
    let mut conn = state.redis.clone();
    let mut jobs = vec![];
    for _ in 0..16 {
        let state = state.clone();
        let bucket = bucket.clone();
        jobs.push(tokio::spawn(async move {
            messenger_server::auth::rate_limit::try_enforce(&state, &bucket, ip, 3, 60)
                .await
                .unwrap()
        }));
    }
    let mut allowed = 0;
    for job in jobs {
        allowed += usize::from(job.await.unwrap());
    }
    assert_eq!(allowed, 3);
    let ttl: i64 = conn.ttl(&key).await.unwrap();
    assert!((1..=60).contains(&ttl));
    let _: () = conn.set(&key, 99).await.unwrap();
    assert!(
        !messenger_server::auth::rate_limit::try_enforce(&state, &bucket, ip, 3, 60)
            .await
            .unwrap()
    );
    let count: i64 = conn.get(&key).await.unwrap();
    assert_eq!(count, 100);
    let before: i64 = conn.pttl(&key).await.unwrap();
    assert!(before > 0);
    tokio::time::sleep(Duration::from_millis(30)).await;
    assert!(
        !messenger_server::auth::rate_limit::try_enforce(&state, &bucket, ip, 3, 60)
            .await
            .unwrap()
    );
    let after: i64 = conn.pttl(&key).await.unwrap();
    assert!(after < before, "live windows must not extend");
    let _: () = conn.pexpire(&key, 1).await.unwrap();
    tokio::time::sleep(Duration::from_millis(10)).await;
    assert!(
        messenger_server::auth::rate_limit::try_enforce(&state, &bucket, ip, 3, 60)
            .await
            .unwrap()
    );
    let ttl: i64 = conn.ttl(&key).await.unwrap();
    assert!(ttl > 0);
    let _: () = conn.del(key).await.unwrap();
}

#[tokio::test]
async fn legacy_storage_is_audited_after_revocation_without_false_quota_or_undercount() {
    let state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (token, user) = register(&app).await;
    let key = format!("avatars/{}", Uuid::new_v4());
    let missing = format!("avatars/{}", Uuid::new_v4());
    let size = 4 * 1024 * 1024;
    assert!(fixture_storage(
        &state,
        &state.config.s3.bucket,
        &key,
        "PUT",
        &[],
        vec![7; size]
    )
    .await
    .status()
    .is_success());
    for object in [&key, &missing] {
        sqlx::query(
            "INSERT INTO avatar_uploads(object_key,user_id,is_complete) VALUES($1,$2,true)",
        )
        .bind(object)
        .bind(user)
        .execute(&state.db)
        .await
        .unwrap();
    }
    sqlx::query("UPDATE users SET avatar_key=$2,avatar_url=$2 WHERE id=$1")
        .bind(user)
        .bind(&key)
        .execute(&state.db)
        .await
        .unwrap();
    let queued = format!("attachments/legacy-missing-{}", Uuid::new_v4());
    sqlx::query("INSERT INTO account_object_deletions(object_key,delete_after,legacy_unverified) VALUES($1,now(),true)").bind(&queued).execute(&state.db).await.unwrap();
    let original = std::env::var("S3_LEGACY_PUT_REVOKED_AT").ok();
    std::env::remove_var("S3_LEGACY_PUT_REVOKED_AT");
    assert!(
        messenger_server::users::deletion::reconcile_legacy_storage(&state)
            .await
            .is_err()
    );
    let pending = app
        .clone()
        .oneshot(request(
            "POST",
            "/users/me/avatar",
            Some(&token),
            Some(json!({"size_bytes":jpeg().len()})),
        ))
        .await
        .unwrap();
    assert_eq!(pending.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(
        app.clone()
            .oneshot(request("GET", "/users/me", Some(&token), None))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    std::env::set_var(
        "S3_LEGACY_PUT_REVOKED_AT",
        (chrono::Utc::now().timestamp() + 300).to_string(),
    );
    assert!(
        messenger_server::users::deletion::reconcile_legacy_storage(&state)
            .await
            .is_err()
    );
    // In this private fixture no root PUT capability was exposed to a client;
    // model the completed operator revocation/drain checkpoint.
    std::env::set_var(
        "S3_LEGACY_PUT_REVOKED_AT",
        (chrono::Utc::now().timestamp() - 61).to_string(),
    );
    messenger_server::users::deletion::reconcile_legacy_storage(&state)
        .await
        .unwrap();
    if let Some(value) = original {
        std::env::set_var("S3_LEGACY_PUT_REVOKED_AT", value);
    } else {
        std::env::remove_var("S3_LEGACY_PUT_REVOKED_AT");
    }
    let used: i64 = sqlx::query_scalar(
        "SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1",
    )
    .bind(user)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(used, size as i64);
    let missing_bytes: i64 = sqlx::query_scalar(
        "SELECT reserved_bytes FROM avatar_uploads WHERE object_key=$1 AND legacy_reconciled",
    )
    .bind(&missing)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(missing_bytes, 0);
    let pending: bool = sqlx::query_scalar(
        "SELECT legacy_unverified FROM account_object_deletions WHERE object_key=$1",
    )
    .bind(&queued)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert!(!pending);
    let policy: (i64, i64) = sqlx::query_as(
        "SELECT user_quota_bytes,storage_budget_bytes FROM server_policy WHERE id=true",
    )
    .fetch_one(&state.db)
    .await
    .unwrap();
    sqlx::query(
        "UPDATE server_policy SET user_quota_bytes=5242880,storage_budget_bytes=0 WHERE id=true",
    )
    .execute(&state.db)
    .await
    .unwrap();
    let allowed = app
        .clone()
        .oneshot(request(
            "POST",
            "/users/me/avatar",
            Some(&token),
            Some(json!({"size_bytes":jpeg().len()})),
        ))
        .await
        .unwrap()
        .status();
    sqlx::query(
        "UPDATE server_policy SET user_quota_bytes=$1,storage_budget_bytes=$2 WHERE id=true",
    )
    .bind(policy.0)
    .bind(policy.1)
    .execute(&state.db)
    .await
    .unwrap();
    assert_eq!(
        allowed,
        StatusCode::OK,
        "missing historical objects must not leave a false 3MiB reservation"
    );
}

#[tokio::test]
async fn chat_delete_and_clear_keep_object_bytes_charged_until_storage_deletion() {
    let state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (token, user) = register(&app).await;
    let (_, peer) = register(&app).await;
    let device: Uuid = sqlx::query_scalar("SELECT id FROM devices WHERE user_id=$1")
        .bind(peer)
        .fetch_one(&state.db)
        .await
        .unwrap();
    for delete_chat in [true, false] {
        let r = app
            .clone()
            .oneshot(request(
                "POST",
                "/chats/direct",
                Some(&token),
                Some(json!({"other_user_id":peer})),
            ))
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let chat = body(r).await["chat_id"].as_str().unwrap().to_owned();
        let r = app
            .clone()
            .oneshot(request(
                "POST",
                "/attachments",
                Some(&token),
                Some(json!({"client_attachment_id":Uuid::new_v4(),"encrypted_size":16})),
            ))
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let intent = body(r).await;
        assert!(put(&intent, vec![1; 16]).await.status().is_success());
        let id = intent["attachment_id"].as_str().unwrap();
        assert_eq!(
            app.clone()
                .oneshot(request(
                    "POST",
                    &format!("/attachments/{id}/complete"),
                    Some(&token),
                    None
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::OK
        );
        let r=app.clone().oneshot(request("POST",&format!("/chats/{chat}/messages"),Some(&token),Some(json!({"client_message_id":Uuid::new_v4(),"attachment_ids":[id],"envelopes":[{"recipient_device_id":device,"message_type":2,"ciphertext":"AA=="}]})))).await.unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let sequence = body(r).await["sequence"].as_i64().unwrap();
        let user_before: i64 = sqlx::query_scalar(
            "SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1",
        )
        .bind(user)
        .fetch_one(&state.db)
        .await
        .unwrap();
        let before: i64 =
            sqlx::query_scalar("SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations")
                .fetch_one(&state.db)
                .await
                .unwrap();
        let (suffix, payload) = if delete_chat {
            ("delete", json!({"for_everyone":true}))
        } else {
            (
                "clear-history",
                json!({"for_everyone":true,"request_id":Uuid::new_v4(),"through_sequence":sequence}),
            )
        };
        let r = app
            .clone()
            .oneshot(request(
                "POST",
                &format!("/chats/{chat}/{suffix}"),
                Some(&token),
                Some(payload),
            ))
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let after: i64 =
            sqlx::query_scalar("SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations")
                .fetch_one(&state.db)
                .await
                .unwrap();
        assert_eq!(after, before);
        let user_after: i64 = sqlx::query_scalar(
            "SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1",
        )
        .bind(user)
        .fetch_one(&state.db)
        .await
        .unwrap();
        assert_eq!(
            user_after, user_before,
            "a living uploader's quota must survive chat deletion until physical cleanup"
        );
        let reserved: i64 = sqlx::query_scalar(
            "SELECT reserved_bytes FROM account_object_deletions WHERE object_key=$1",
        )
        .bind(intent["object_key"].as_str().unwrap())
        .fetch_one(&state.db)
        .await
        .unwrap();
        assert_eq!(reserved, 16);
    }
}

#[tokio::test]
async fn avatar_signed_size_and_both_commit_routes_fail_closed() {
    let state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (token, user) = register(&app).await;
    for data in [
        None,
        Some(json!({"size_bytes":0})),
        Some(json!({"size_bytes":3*1024*1024+1})),
        Some(json!({"size_bytes":-1})),
    ] {
        assert_eq!(
            app.clone()
                .oneshot(request("POST", "/users/me/avatar", Some(&token), data))
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM avatar_uploads WHERE user_id=$1")
        .bind(user)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    let bytes = jpeg();
    let intent = avatar(&app, &token, bytes.len()).await;
    assert!(intent["upload_url"]
        .as_str()
        .unwrap()
        .contains("content-length"));
    assert!(!put(&intent, vec![0; bytes.len() + 1])
        .await
        .status()
        .is_success());
    assert!(put(&intent, bytes.clone()).await.status().is_success());
    let key = intent["object_key"].as_str().unwrap();
    let completed = commit(&app, &token, key).await;
    assert_eq!(completed.status(), StatusCode::OK);
    let first = body(completed).await;
    let repeated = commit(&app, &token, key).await;
    assert_eq!(repeated.status(), StatusCode::OK);
    assert_eq!(
        body(repeated).await["avatar_version"],
        first["avatar_version"]
    );
    let invalid = avatar(&app, &token, bytes.len()).await;
    assert!(put(&invalid, vec![0; bytes.len()])
        .await
        .status()
        .is_success());
    let bad = invalid["object_key"].as_str().unwrap();
    assert_eq!(
        commit(&app, &token, bad).await.status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        app.clone()
            .oneshot(request(
                "PATCH",
                "/users/me",
                Some(&token),
                Some(json!({"avatar_url":bad}))
            ))
            .await
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST
    );
    let current: Option<String> = sqlx::query_scalar("SELECT avatar_key FROM users WHERE id=$1")
        .bind(user)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(current.as_deref(), Some(key));
    // An unsigned CopyObject header must not turn the limited PUT into an
    // arbitrary private-source copy under the delegated storage capability.
    let source = format!("attachments/copy-boundary-{}", Uuid::new_v4());
    let (source_url, _) = state
        .s3
        .presign_put_sized(&source, (bytes.len() + 1000) as i64)
        .unwrap();
    reqwest::Client::new()
        .put(source_url)
        .body(vec![1; bytes.len() + 1000])
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap();
    let target = avatar(&app, &token, bytes.len()).await;
    let copied = reqwest::Client::new()
        .put(target["upload_url"].as_str().unwrap())
        .header(
            "x-amz-copy-source",
            format!("/{}/{}", state.config.s3.bucket, source),
        )
        .body(bytes.clone())
        .send()
        .await
        .unwrap();
    let copied_size = state
        .s3
        .head_object(target["object_key"].as_str().unwrap())
        .await
        .unwrap();
    assert!(
        !copied.status().is_success() || copied_size.is_none(),
        "unsigned copy-source must not be accepted"
    );
    let multipart = format!("attachments/copy-part-boundary-{}", Uuid::new_v4());
    let upload_id = state.s3.begin_multipart(&multipart).await.unwrap();
    let part_url = state
        .s3
        .presign_part(&multipart, &upload_id, 1, bytes.len() as i64)
        .unwrap();
    let copied_part = reqwest::Client::new()
        .put(&part_url)
        .header(
            "x-amz-copy-source",
            format!("/{}/{}", state.config.s3.bucket, source),
        )
        .body(bytes.clone())
        .send()
        .await
        .unwrap();
    assert!(
        !copied_part.status().is_success(),
        "UploadPartCopy must not bypass a signed part size"
    );
    let normal_part = reqwest::Client::new()
        .put(part_url)
        .body(bytes.clone())
        .send()
        .await
        .unwrap();
    assert!(normal_part.status().is_success());
    let etag = normal_part.headers()["etag"].to_str().unwrap().to_owned();
    state
        .s3
        .finish_multipart(&multipart, &upload_id, vec![(1, etag)])
        .await
        .unwrap();
    assert_eq!(
        state.s3.head_object(&multipart).await.unwrap(),
        Some(bytes.len() as u64)
    );
    state.s3.delete_object(&multipart).await.unwrap();
    state.s3.delete_object(&source).await.unwrap();
}

#[tokio::test]
async fn refreshed_avatar_file_and_part_leases_cover_in_flight_requests() {
    let state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (token, _) = register(&app).await;
    let minimum =
        state.config.s3.presign_ttl_seconds + state.config.s3.max_upload_duration_seconds + 59;
    let intent = avatar(&app, &token, jpeg().len()).await;
    let avatar_lease:f64=sqlx::query_scalar("SELECT EXTRACT(EPOCH FROM upload_lease_until-now())::double precision FROM avatar_uploads WHERE object_key=$1").bind(intent["object_key"].as_str().unwrap()).fetch_one(&state.db).await.unwrap();
    assert!(avatar_lease >= minimum as f64);
    let client_id = Uuid::new_v4();
    let payload = json!({"client_attachment_id":client_id,"encrypted_size":16});
    let mut attachment = None;
    for _ in 0..2 {
        let r = app
            .clone()
            .oneshot(request(
                "POST",
                "/attachments",
                Some(&token),
                Some(payload.clone()),
            ))
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let intent = body(r).await;
        let id = Uuid::parse_str(intent["attachment_id"].as_str().unwrap()).unwrap();
        if let Some(previous) = attachment {
            assert_eq!(previous, id);
        }
        attachment = Some(id);
        let lease:f64=sqlx::query_scalar("SELECT EXTRACT(EPOCH FROM upload_lease_until-now())::double precision FROM attachments WHERE id=$1").bind(id).fetch_one(&state.db).await.unwrap();
        assert!(lease >= minimum as f64);
        sqlx::query("UPDATE attachments SET created_at=now()-interval '2 days',upload_lease_until=now()-interval '1 second' WHERE id=$1").bind(id).execute(&state.db).await.unwrap();
    }
    let id = attachment.unwrap();
    let r = app
        .clone()
        .oneshot(request(
            "POST",
            &format!("/attachments/{id}/multipart"),
            Some(&token),
            Some(json!({})),
        ))
        .await
        .unwrap();
    assert_eq!(r.status(), StatusCode::OK);
    let lease:f64=sqlx::query_scalar("SELECT EXTRACT(EPOCH FROM upload_lease_until-now())::double precision FROM attachments WHERE id=$1").bind(id).fetch_one(&state.db).await.unwrap();
    assert!(lease >= minimum as f64);
    assert!(
        !messenger_server::attachments::cleanup::delete_abandoned(&state, id, None)
            .await
            .unwrap()
    );
    let charged: i64 = sqlx::query_scalar("SELECT encrypted_size FROM attachments WHERE id=$1")
        .bind(id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(charged, 16);
}

#[tokio::test]
async fn avatar_reservations_serialize_and_share_attachment_and_backup_budget() {
    let state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (token, user) = register(&app).await;
    let size = jpeg().len() as i64;
    let policy:(i64,i64,i64)=sqlx::query_as("SELECT max_file_bytes,user_quota_bytes,storage_budget_bytes FROM server_policy WHERE id=true").fetch_one(&state.db).await.unwrap();
    sqlx::query(
        "UPDATE server_policy SET user_quota_bytes=$1,storage_budget_bytes=0 WHERE id=true",
    )
    .bind(size * 2 - 1)
    .execute(&state.db)
    .await
    .unwrap();
    let a = app.clone().oneshot(request(
        "POST",
        "/users/me/avatar",
        Some(&token),
        Some(json!({"size_bytes":size})),
    ));
    let b = app.clone().oneshot(request(
        "POST",
        "/users/me/avatar",
        Some(&token),
        Some(json!({"size_bytes":size})),
    ));
    let (a, b) = tokio::join!(a, b);
    let statuses = [a.unwrap().status(), b.unwrap().status()];
    let used: i64 = sqlx::query_scalar(
        "SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations WHERE owner_id=$1",
    )
    .bind(user)
    .fetch_one(&state.db)
    .await
    .unwrap();
    sqlx::query("UPDATE server_policy SET user_quota_bytes=$1 WHERE id=true")
        .bind(size)
        .execute(&state.db)
        .await
        .unwrap();
    let attachment = app
        .clone()
        .oneshot(request(
            "POST",
            "/attachments",
            Some(&token),
            Some(json!({"client_attachment_id":Uuid::new_v4(),"encrypted_size":1})),
        ))
        .await
        .unwrap()
        .status();
    let total: i64 =
        sqlx::query_scalar("SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations")
            .fetch_one(&state.db)
            .await
            .unwrap();
    sqlx::query(
        "UPDATE server_policy SET user_quota_bytes=0,storage_budget_bytes=$1 WHERE id=true",
    )
    .bind(total)
    .execute(&state.db)
    .await
    .unwrap();
    let (other, _) = register(&app).await;
    let full = app
        .clone()
        .oneshot(request(
            "POST",
            "/users/me/avatar",
            Some(&other),
            Some(json!({"size_bytes":1})),
        ))
        .await
        .unwrap()
        .status();
    sqlx::query("UPDATE server_policy SET max_file_bytes=$1,user_quota_bytes=$2,storage_budget_bytes=$3 WHERE id=true").bind(policy.0).bind(policy.1).bind(policy.2).execute(&state.db).await.unwrap();
    assert_eq!(statuses.iter().filter(|s| **s == StatusCode::OK).count(), 1);
    assert_eq!(used, size);
    assert_eq!(attachment, StatusCode::BAD_REQUEST);
    assert_eq!(full, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn avatar_cleanup_and_account_deletion_keep_bytes_charged_until_confirmed_removal() {
    let mut state = fixture().await;
    state.config.s3.presign_ttl_seconds = 1;
    state.s3 = Arc::new(messenger_server::storage::S3Client::new(
        state.config.s3.clone(),
    ));
    state.s3.ensure_bucket().await.unwrap();
    let app = messenger_server::app(state.clone());
    let (token, user) = register(&app).await;
    let bytes = jpeg();
    let intent = avatar(&app, &token, bytes.len()).await;
    let key = intent["object_key"].as_str().unwrap();
    assert!(put(&intent, bytes.clone()).await.status().is_success());
    assert_eq!(commit(&app, &token, key).await.status(), StatusCode::OK);
    let before: i64 =
        sqlx::query_scalar("SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations")
            .fetch_one(&state.db)
            .await
            .unwrap();
    let deleted = app
        .clone()
        .oneshot(request(
            "POST",
            "/users/me/delete",
            Some(&token),
            Some(json!({"password":"security-fixture-password"})),
        ))
        .await
        .unwrap();
    assert_eq!(deleted.status(), StatusCode::NO_CONTENT);
    let after: i64 =
        sqlx::query_scalar("SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations")
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(after, before);
    let queued: i64 = sqlx::query_scalar(
        "SELECT reserved_bytes FROM account_object_deletions WHERE object_key=$1",
    )
    .bind(key)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(queued, bytes.len() as i64);
    assert_eq!(
        state.s3.head_object(key).await.unwrap(),
        Some(bytes.len() as u64)
    );
    tokio::time::sleep(Duration::from_secs(2)).await;
    assert!(!put(&intent, bytes.clone()).await.status().is_success());
    // Advance only the cleanup grace fixture after the real S3 PUT expired.
    sqlx::query("UPDATE account_object_deletions SET delete_after=now()-interval '1 second' WHERE object_key=$1").bind(key).execute(&state.db).await.unwrap();
    assert!(
        messenger_server::users::deletion::sweep_deleted_objects(&state)
            .await
            .unwrap()
            > 0
    );
    assert_eq!(state.s3.head_object(key).await.unwrap(), None);
    let gone: i64 =
        sqlx::query_scalar("SELECT COALESCE(sum(bytes),0)::bigint FROM storage_reservations")
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(gone, before - bytes.len() as i64);
    let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)")
        .bind(user)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(!exists);
}

#[tokio::test]
async fn huge_or_duplicate_attachment_sets_are_rejected_before_user_transaction_locks() {
    let state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (token, user) = register(&app).await;
    let saved = app
        .clone()
        .oneshot(request("GET", "/chats/saved", Some(&token), None))
        .await
        .unwrap();
    assert_eq!(saved.status(), StatusCode::OK);
    let saved = body(saved).await;
    let chat = saved["chat_id"].as_str().unwrap();
    let id = Uuid::new_v4();
    let mut guard = state.db.begin().await.unwrap();
    sqlx::query("SELECT id FROM users WHERE id=$1 FOR UPDATE")
        .bind(user)
        .execute(&mut *guard)
        .await
        .unwrap();
    let large =
        json!({"client_message_id":Uuid::new_v4(),"envelopes":[],"attachment_ids":vec![id;50_000]});
    assert!(large.to_string().len() < 2 * 1024 * 1024);
    let response = tokio::time::timeout(
        Duration::from_secs(2),
        app.clone().oneshot(request(
            "POST",
            &format!("/chats/{chat}/messages"),
            Some(&token),
            Some(large),
        )),
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert!(body(response).await["error"]["message"]
        .as_str()
        .unwrap()
        .contains("10 attachments"));
    let duplicate = json!({"client_message_id":Uuid::new_v4(),"envelopes":[],"attachment_ids":[id.to_string(),id.simple().to_string().to_uppercase()]});
    let response = tokio::time::timeout(
        Duration::from_secs(2),
        app.clone().oneshot(request(
            "POST",
            &format!("/chats/{chat}/messages"),
            Some(&token),
            Some(duplicate),
        )),
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert!(body(response).await["error"]["message"]
        .as_str()
        .unwrap()
        .contains("distinct"));
    guard.rollback().await.unwrap();
    let mut ids = vec![];
    for _ in 0..10 {
        let r = app
            .clone()
            .oneshot(request(
                "POST",
                "/attachments",
                Some(&token),
                Some(json!({"client_attachment_id":Uuid::new_v4(),"encrypted_size":16})),
            ))
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let intent = body(r).await;
        assert!(put(&intent, vec![1; 16]).await.status().is_success());
        let id = intent["attachment_id"].as_str().unwrap().to_owned();
        assert_eq!(
            app.clone()
                .oneshot(request(
                    "POST",
                    &format!("/attachments/{id}/complete"),
                    Some(&token),
                    None
                ))
                .await
                .unwrap()
                .status(),
            StatusCode::OK
        );
        ids.push(id);
    }
    let payload = json!({"client_message_id":Uuid::new_v4(),"envelopes":[],"attachment_ids":ids});
    let mut sent = None;
    for _ in 0..2 {
        let r = app
            .clone()
            .oneshot(request(
                "POST",
                &format!("/chats/{chat}/messages"),
                Some(&token),
                Some(payload.clone()),
            ))
            .await
            .unwrap();
        assert_eq!(r.status(), StatusCode::OK);
        let id = body(r).await["id"].as_str().unwrap().to_owned();
        if let Some(previous) = &sent {
            assert_eq!(previous, &id);
        }
        sent = Some(id);
    }
    let count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM attachments WHERE message_id=$1 AND status='attached'",
    )
    .bind(Uuid::parse_str(sent.as_ref().unwrap()).unwrap())
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(count, 10);
}

async fn ws_server(state: AppState) -> (SocketAddr, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            messenger_server::app(state).into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .unwrap();
    });
    (address, task)
}
async fn connect(
    address: SocketAddr,
    token: &str,
) -> Result<
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
    tokio_tungstenite::tungstenite::Error,
> {
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;
    let mut r = format!("ws://{address}/ws").into_client_request().unwrap();
    r.headers_mut()
        .insert("authorization", format!("Bearer {token}").parse().unwrap());
    tokio_tungstenite::connect_async(r).await.map(|p| p.0)
}
#[tokio::test]
async fn real_websockets_allow_overlap_reject_overbooking_and_release_on_close() {
    let state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (token, _) = register(&app).await;
    let (address, task) = ws_server(state.clone()).await;
    let mut sockets = vec![];
    for _ in 0..4 {
        sockets.push(connect(address, &token).await.unwrap());
    }
    match connect(address, &token).await {
        Err(tokio_tungstenite::tungstenite::Error::Http(r)) => {
            assert_eq!(r.status(), StatusCode::TOO_MANY_REQUESTS)
        }
        _ => panic!("fifth device socket was not rejected"),
    }
    let mut closed = sockets.pop().unwrap();
    closed.close(None).await.unwrap();
    drop(closed);
    tokio::time::timeout(Duration::from_secs(3), async {
        while state.resources.sockets.active_connections() > 3 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    let replacement = connect(address, &token).await.unwrap();
    drop(replacement);
    for mut socket in sockets {
        let _ = socket.close(None).await;
    }
    task.abort();
}
#[tokio::test]
async fn malformed_websocket_frames_do_not_need_sql_and_cannot_bypass_frame_budget() {
    use tokio_tungstenite::tungstenite::Message;
    let state = fixture().await;
    let app = messenger_server::app(state.clone());
    let (token, _) = register(&app).await;
    let (address, task) = ws_server(state.clone()).await;
    let mut socket = connect(address, &token).await.unwrap();
    socket
        .send(Message::Text(json!({"type":"PING"}).to_string()))
        .await
        .unwrap();
    assert!(socket.next().await.unwrap().unwrap().is_text());
    tokio::time::sleep(Duration::from_millis(40)).await;
    state.db.close().await;
    for _ in 0..85 {
        if socket.send(Message::Text("{".into())).await.is_err() {
            break;
        }
    }
    let (mut errors, mut closed) = (0usize, false);
    for _ in 0..90 {
        match tokio::time::timeout(Duration::from_secs(2), socket.next()).await {
            Ok(Some(Ok(Message::Text(text)))) => {
                let v: Value = serde_json::from_str(&text).unwrap();
                if v["type"] == "ERROR" {
                    errors += 1;
                }
            }
            Ok(Some(Ok(Message::Close(_)))) | Ok(None) | Ok(Some(Err(_))) => {
                closed = true;
                break;
            }
            _ => break,
        }
    }
    assert!(
        errors > 0 && errors <= 80,
        "malformed frames must be handled without a per-frame SQL check"
    );
    assert!(closed, "frame budget must close the abusive connection");
    task.abort();
}

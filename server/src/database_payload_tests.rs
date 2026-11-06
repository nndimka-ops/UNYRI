//! Opt-in in-process HTTP checks against disposable PG/Redis only. S3 is an
//! unreachable fixture: signed-URL authorization, not storage transport, is tested.
use super::*;
use crate::{
    config::{Config, S3Config, TurnConfig},
    state::AppState,
};
use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;

async fn state() -> AppState {
    let database =
        std::env::var("PAYLOAD_TEST_DATABASE_URL").expect("explicit disposable database required");
    let redis_url =
        std::env::var("PAYLOAD_TEST_REDIS_URL").expect("explicit disposable Redis required");
    let parsed = reqwest::Url::parse(&database).unwrap();
    assert_eq!(
        (parsed.host_str(), parsed.port(), parsed.path()),
        (Some("127.0.0.1"), Some(55449), "/payload_test")
    );
    let parsed = reqwest::Url::parse(&redis_url).unwrap();
    assert_eq!(
        (parsed.host_str(), parsed.port()),
        (Some("127.0.0.1"), Some(56449))
    );
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(10)
        .connect(&database)
        .await
        .unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    let redis = redis::Client::open(redis_url.clone())
        .unwrap()
        .get_connection_manager()
        .await
        .unwrap();
    let s3 = S3Config {
        endpoint_url: "http://127.0.0.1:1".into(),
        public_endpoint_url: "http://127.0.0.1:1".into(),
        region: "fixture".into(),
        bucket: "fixture".into(),
        access_key_id: "fixture".into(),
        secret_access_key: "fixture".into(),
        presign_ttl_seconds: 60,
        max_upload_duration_seconds: 0,
        upload_access_key_id: String::new(),
        upload_secret_access_key: String::new(),
    };
    let config = Config {
        host: "127.0.0.1".into(),
        port: 0,
        database_url: database,
        database_max_connections: 10,
        redis_url,
        jwt_secret: "payload-isolated-fixture-token-secret".into(),
        trusted_proxies: vec![],
        jwt_issuer: "fixture".into(),
        jwt_audience: "fixture".into(),
        access_token_ttl_seconds: 900,
        refresh_token_ttl_seconds: 900,
        s3: s3.clone(),
        turn: TurnConfig {
            stun_urls: vec![],
            turn_urls: vec![],
            shared_secret: "fixture".into(),
            credential_ttl_seconds: 60,
        },
        fcm: Default::default(),
    };
    AppState {
        resources: Default::default(),
        db,
        redis,
        config,
        ws_registry: Default::default(),
        s3: std::sync::Arc::new(crate::storage::S3Client::new(s3)),
        push: crate::push::disabled(),
    }
}
async fn request(
    app: &axum::Router,
    method: &str,
    path: &str,
    token: &str,
    body: Value,
) -> (StatusCode, Value) {
    let mut req = Request::builder()
        .method(method)
        .uri(path)
        .header("content-type", "application/json")
        .extension(ConnectInfo(std::net::SocketAddr::from((
            [10, 1, rand::random(), rand::random()],
            12345,
        ))));
    if !token.is_empty() {
        req = req.header("authorization", format!("Bearer {token}"));
    }
    let response = app
        .clone()
        .oneshot(req.body(Body::from(body.to_string())).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap()
        },
    )
}
async fn account(app: &axum::Router) -> (String, Uuid, Uuid) {
    let name = format!("p{}", &Uuid::new_v4().simple().to_string()[..24]);
    let (status,body)=request(app,"POST","/auth/register","",json!({"username":name,"password":"fixture-password-123","display_name":"Payload fixture","installation_id":Uuid::new_v4(),"device_platform":"android","device_name":"Disposable"})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    (
        body["access_token"].as_str().unwrap().into(),
        body["user"]["id"].as_str().unwrap().parse().unwrap(),
        body["device_id"].as_str().unwrap().parse().unwrap(),
    )
}
async fn saved(app: &axum::Router, token: &str) -> Uuid {
    let (status, body) = request(app, "GET", "/chats/saved", token, json!({})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body["chat_id"].as_str().unwrap().parse().unwrap()
}
fn content(id: Uuid) -> Value {
    json!({"client_message_id":id,"envelopes":[]})
}
fn keys(identity: u8, start: i32, count: i32) -> Value {
    json!({"identity_key":STANDARD.encode(vec![identity;33]),"registration_id":1,
        "signed_prekey":{"key_id":1,"public_key":STANDARD.encode(vec![2;33]),"signature":STANDARD.encode(vec![3;64])},
        "kyber_prekey":{"key_id":1,"public_key":STANDARD.encode(vec![4;1569]),"signature":STANDARD.encode(vec![5;64])},
        "one_time_prekeys":(start..start+count).map(|key|json!({"key_id":key,"public_key":STANDARD.encode(vec![6;33])})).collect::<Vec<_>>()})
}
async fn total(state: &AppState) -> i64 {
    sqlx::query_scalar("SELECT bytes FROM database_payload_global WHERE id=true")
        .fetch_one(&state.db)
        .await
        .unwrap()
}
async fn owner_bytes(state: &AppState, user: Uuid) -> i64 {
    sqlx::query_scalar(
        "SELECT COALESCE((SELECT bytes FROM database_payload_accounts WHERE owner_id=$1),0)",
    )
    .bind(user)
    .fetch_one(&state.db)
    .await
    .unwrap()
}

#[tokio::test]
#[ignore = "requires disposable payload fixture 55449/56449"]
async fn payload_quota_is_atomic_idempotent_and_refunded_on_cascade() {
    let state = state().await;
    let app = crate::app(state.clone());
    let (token, user, device) = account(&app).await;
    let chat = saved(&app, &token).await;
    let path = format!("/chats/{chat}/messages");
    let before = total(&state).await;
    let id = Uuid::new_v4();
    let (status, body) = request(&app, "POST", &path, &token, content(id)).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let message: Uuid = body["id"].as_str().unwrap().parse().unwrap();
    assert_eq!(total(&state).await - before, 1024);
    assert_eq!(
        request(&app, "POST", &path, &token, content(id)).await.0,
        StatusCode::OK
    );
    assert_eq!(total(&state).await - before, 1024);
    let old: (i64, i64) = sqlx::query_as(
        "SELECT database_user_quota_bytes,database_budget_bytes FROM server_policy WHERE id=true",
    )
    .fetch_one(&state.db)
    .await
    .unwrap();
    sqlx::query("UPDATE server_policy SET database_user_quota_bytes=$1,database_budget_bytes=$2 WHERE id=true").bind(2048_i64).bind(total(&state).await+1024).execute(&state.db).await.unwrap();
    let (a, b) = tokio::join!(
        request(&app, "POST", &path, &token, content(Uuid::new_v4())),
        request(&app, "POST", &path, &token, content(Uuid::new_v4()))
    );
    assert_eq!(
        [a.0, b.0].iter().filter(|s| **s == StatusCode::OK).count(),
        1,
        "{a:?} {b:?}"
    );
    assert_eq!(owner_bytes(&state, user).await, 2048);
    assert_eq!(total(&state).await - before, 2048);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM messages WHERE chat_id=$1")
            .bind(chat)
            .fetch_one(&state.db)
            .await
            .unwrap(),
        2
    );
    sqlx::query("UPDATE server_policy SET database_user_quota_bytes=$1,database_budget_bytes=$2 WHERE id=true").bind(old.0).bind(old.1).execute(&state.db).await.unwrap();
    let too_large = json!({"client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":device,"message_type":2,"ciphertext":STANDARD.encode(vec![0;MAX_CIPHERTEXT_BYTES+1])}]});
    assert_eq!(
        request(&app, "POST", &path, &token, too_large).await.0,
        StatusCode::BAD_REQUEST
    );
    let mut tx = state.db.begin().await.unwrap();
    let rolled_back:Uuid=sqlx::query_scalar("INSERT INTO messages(chat_id,sender_id,sender_device_id,client_message_id,encryption_version) VALUES($1,$2,$3,$4,1) RETURNING id")
        .bind(chat).bind(user).bind(device).bind(Uuid::new_v4()).fetch_one(&mut *tx).await.unwrap();
    reserve_message(&mut tx, rolled_back, user, 99999)
        .await
        .unwrap();
    tx.rollback().await.unwrap();
    assert_eq!(owner_bytes(&state, user).await, 2048);
    assert!(
        !sqlx::query_scalar::<_, bool>("SELECT EXISTS(SELECT 1 FROM messages WHERE id=$1)")
            .bind(rolled_back)
            .fetch_one(&state.db)
            .await
            .unwrap()
    );
    sqlx::query("DELETE FROM messages WHERE chat_id=$1")
        .bind(chat)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(owner_bytes(&state, user).await, 0);
    assert_eq!(total(&state).await, before);
    let (other_token, other_user, _) = account(&app).await;
    let other_chat = saved(&app, &other_token).await;
    let other_path = format!("/chats/{other_chat}/messages");
    sqlx::query("UPDATE server_policy SET database_budget_bytes=$1 WHERE id=true")
        .bind(before + 1024)
        .execute(&state.db)
        .await
        .unwrap();
    let (a, b) = tokio::join!(
        request(&app, "POST", &path, &token, content(Uuid::new_v4())),
        request(
            &app,
            "POST",
            &other_path,
            &other_token,
            content(Uuid::new_v4())
        )
    );
    assert_eq!(
        [a.0, b.0].iter().filter(|s| **s == StatusCode::OK).count(),
        1,
        "global admission must serialize independent owners: {a:?} {b:?}"
    );
    assert_eq!(
        owner_bytes(&state, user).await + owner_bytes(&state, other_user).await,
        1024
    );
    sqlx::query("UPDATE server_policy SET database_budget_bytes=$1 WHERE id=true")
        .bind(old.1)
        .execute(&state.db)
        .await
        .unwrap();
    sqlx::query("DELETE FROM messages WHERE chat_id=ANY($1)")
        .bind(vec![chat, other_chat])
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(total(&state).await, before);
}

#[tokio::test]
#[ignore = "requires disposable payload fixture 55449/56449"]
async fn payload_prekey_retirement_cannot_resurrect_and_inventory_is_bounded() {
    let state = state().await;
    let app = crate::app(state.clone());
    let (token, user, device) = account(&app).await;
    let before = total(&state).await;
    assert_eq!(
        request(&app, "POST", "/keys", &token, keys(1, 0, 20))
            .await
            .0,
        StatusCode::OK
    );
    let first = owner_bytes(&state, user).await;
    assert!(first > 2000);
    assert_eq!(
        request(
            &app,
            "GET",
            &format!("/keys/bundles/{user}"),
            &token,
            json!({})
        )
        .await
        .0,
        StatusCode::OK
    );
    let (status, body) = request(&app, "POST", "/keys", &token, keys(1, 0, 20)).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["one_time_prekey_count"], 19);
    let floor: i32 = sqlx::query_scalar(
        "SELECT one_time_prekey_retired_through FROM device_identity_keys WHERE device_id=$1",
    )
    .bind(device)
    .fetch_one(&state.db)
    .await
    .unwrap();
    assert_eq!(floor, 0);
    for batch in 0..4 {
        assert_eq!(
            request(
                &app,
                "POST",
                "/keys",
                &token,
                keys(1, 20 + batch * 200, 200)
            )
            .await
            .0,
            StatusCode::OK
        );
    }
    assert_eq!(
        request(&app, "POST", "/keys", &token, keys(1, 820, 181))
            .await
            .0,
        StatusCode::OK
    );
    let full = owner_bytes(&state, user).await;
    assert_eq!(
        request(&app, "POST", "/keys", &token, keys(1, 1001, 1))
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(owner_bytes(&state, user).await, full);
    let (status, body) = request(&app, "POST", "/keys", &token, keys(9, 0, 20)).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["one_time_prekey_count"], 20);
    assert_eq!(owner_bytes(&state, user).await, first);
    sqlx::query("DELETE FROM devices WHERE id=$1")
        .bind(device)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(owner_bytes(&state, user).await, 0);
    assert_eq!(total(&state).await, before);
}

#[tokio::test]
#[ignore = "requires disposable payload fixture 55449/56449"]
async fn payload_deleted_attachment_revokes_fresh_urls_and_retry_cannot_reattach() {
    let state = state().await;
    let app = crate::app(state.clone());
    let (token, user, device) = account(&app).await;
    let chat = saved(&app, &token).await;
    let path = format!("/chats/{chat}/messages");
    let original = Uuid::new_v4();
    let (status, body) = request(&app, "POST", &path, &token, content(original)).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let message: Uuid = body["id"].as_str().unwrap().parse().unwrap();
    let attachment = Uuid::new_v4();
    let key = format!("attachments/{attachment}");
    sqlx::query("INSERT INTO attachments(id,client_attachment_id,uploader_user_id,uploader_device_id,object_key,encrypted_size,status,message_id) VALUES($1,$1,$2,$3,$4,8,'attached',$5)")
        .bind(attachment).bind(user).bind(device).bind(&key).bind(message).execute(&state.db).await.unwrap();
    assert_eq!(
        request(
            &app,
            "GET",
            &format!("/attachments/{attachment}/download"),
            &token,
            json!({})
        )
        .await
        .0,
        StatusCode::OK
    );
    let old_quota: i64 =
        sqlx::query_scalar("SELECT database_user_quota_bytes FROM server_policy WHERE id=true")
            .fetch_one(&state.db)
            .await
            .unwrap();
    let original_allocation = owner_bytes(&state, user).await;
    sqlx::query("UPDATE server_policy SET database_user_quota_bytes=$1 WHERE id=true")
        .bind(original_allocation)
        .execute(&state.db)
        .await
        .unwrap();
    let deletion = json!({"client_message_id":Uuid::new_v4(),"kind":"delete","target_message_id":message,"envelopes":[]});
    assert_eq!(
        request(&app, "POST", &path, &token, deletion.clone())
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        request(
            &app,
            "GET",
            &format!("/attachments/{attachment}/download"),
            &token,
            json!({})
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        request(&app, "POST", &path, &token, deletion).await.0,
        StatusCode::OK
    );
    assert_eq!(
        owner_bytes(&state, user).await,
        original_allocation,
        "ordinary delete must work at a full quota without adding a new allocation"
    );
    let duplicate = json!({"client_message_id":Uuid::new_v4(),"kind":"delete","target_message_id":message,"envelopes":[]});
    assert_eq!(
        request(&app, "POST", &path, &token, duplicate).await.0,
        StatusCode::OK
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM messages WHERE chat_id=$1")
            .bind(chat)
            .fetch_one(&state.db)
            .await
            .unwrap(),
        2,
        "fresh duplicate deletion IDs must not accumulate"
    );
    sqlx::query("UPDATE server_policy SET database_user_quota_bytes=$1 WHERE id=true")
        .bind(old_quota)
        .execute(&state.db)
        .await
        .unwrap();
    let queued:(i64,bool)=sqlx::query_as("SELECT reserved_bytes,delete_after>now() FROM account_object_deletions WHERE object_key=$1").bind(&key).fetch_one(&state.db).await.unwrap();
    assert_eq!(queued, (8, true));
    sqlx::query("UPDATE account_object_deletions SET delete_after=now()-interval '1 second' WHERE object_key=$1").bind(&key).execute(&state.db).await.unwrap();
    crate::users::deletion::sweep_deleted_objects(&state)
        .await
        .unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT reserved_bytes FROM account_object_deletions WHERE object_key=$1"
        )
        .bind(&key)
        .fetch_one(&state.db)
        .await
        .unwrap(),
        8,
        "storage failure must preserve the durable deletion charge"
    );
    let fresh = Uuid::new_v4();
    sqlx::query("INSERT INTO attachments(id,client_attachment_id,uploader_user_id,uploader_device_id,object_key,encrypted_size,status) VALUES($1,$1,$2,$3,$4,8,'uploaded')")
        .bind(fresh).bind(user).bind(device).bind(format!("attachments/{fresh}")).execute(&state.db).await.unwrap();
    let mut retry = content(original);
    retry["attachment_ids"] = json!([fresh]);
    assert_eq!(
        request(&app, "POST", &path, &token, retry).await.0,
        StatusCode::OK
    );
    let linked: Option<Uuid> = sqlx::query_scalar("SELECT message_id FROM attachments WHERE id=$1")
        .bind(fresh)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert!(linked.is_none());
    sqlx::query("UPDATE attachments SET message_id=$2,status='attached' WHERE id=$1")
        .bind(fresh)
        .bind(message)
        .execute(&state.db)
        .await
        .unwrap();
    assert_eq!(
        request(
            &app,
            "GET",
            &format!("/attachments/{fresh}/download"),
            &token,
            json!({})
        )
        .await
        .0,
        StatusCode::NOT_FOUND,
        "legacy malformed attachment state must also be denied"
    );
    let charged = owner_bytes(&state, user).await;
    assert!(charged > 0);
    assert_eq!(
        request(
            &app,
            "POST",
            "/users/me/delete",
            &token,
            json!({"password":"fixture-password-123"})
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(owner_bytes(&state, user).await, 0);
}

#[tokio::test]
#[ignore = "requires disposable payload fixture 55449/56449"]
async fn payload_crossed_identity_fanout_and_send_preserve_lock_order() {
    let state = state().await;
    let app = crate::app(state.clone());
    let before = total(&state).await;
    let (a, _, a_device) = account(&app).await;
    let (b, b_user, _) = account(&app).await;
    assert_eq!(
        request(&app, "POST", "/keys", &a, keys(1, 0, 20)).await.0,
        StatusCode::OK
    );
    assert_eq!(
        request(&app, "POST", "/keys", &b, keys(1, 0, 20)).await.0,
        StatusCode::OK
    );
    let (status, body) = request(
        &app,
        "POST",
        "/chats/direct",
        &a,
        json!({"other_user_id":b_user}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let chat = body["chat_id"].as_str().unwrap();
    let path = format!("/chats/{chat}/messages");
    for rotation in 2..6 {
        let message = json!({"client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":a_device,"message_type":2,"ciphertext":STANDARD.encode(vec![1;512])}]});
        let pair = tokio::time::timeout(std::time::Duration::from_secs(10), async {
            tokio::join!(
                request(&app, "POST", "/keys", &a, keys(rotation, 0, 20)),
                request(&app, "POST", &path, &b, message)
            )
        })
        .await
        .expect("crossed operations must not deadlock");
        assert_eq!(
            (pair.0 .0, pair.1 .0),
            (StatusCode::OK, StatusCode::OK),
            "{pair:?}"
        );
    }
    for token in [&a, &b] {
        assert_eq!(
            request(
                &app,
                "POST",
                "/users/me/delete",
                token,
                json!({"password":"fixture-password-123"})
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
    }
    assert_eq!(
        total(&state).await,
        before,
        "account cascade must release both message and key reservations"
    );
}

#[tokio::test]
#[ignore = "requires disposable payload fixture 55449/56449"]
async fn payload_http_byte_boundaries_preserve_normal_media_metadata() {
    let state = state().await;
    let app = crate::app(state.clone());
    let (token, user, device) = account(&app).await;
    let mut oversized = keys(1, 0, 20);
    oversized["identity_key"] = json!(STANDARD.encode(vec![1; MAX_PUBLIC_KEY_BYTES + 1]));
    assert_eq!(
        request(&app, "POST", "/keys", &token, oversized).await.0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(owner_bytes(&state, user).await, 0);
    assert_eq!(
        request(&app, "POST", "/keys", &token, keys(1, 0, 20))
            .await
            .0,
        StatusCode::OK,
        "serialized Kyber1569 and normal Signal33/64 must fit"
    );
    let chat = saved(&app, &token).await;
    let path = format!("/chats/{chat}/messages");
    for size in [64 * 1024, MAX_CIPHERTEXT_BYTES] {
        let body = json!({"client_message_id":Uuid::new_v4(),"envelopes":[{"recipient_device_id":device,"message_type":1,"ciphertext":STANDARD.encode(vec![7;size])}]});
        let (status, response) = request(&app, "POST", &path, &token, body).await;
        assert_eq!(status, StatusCode::OK, "{response}");
    }
    assert_eq!(
        request(
            &app,
            "POST",
            "/users/me/delete",
            &token,
            json!({"password":"fixture-password-123"})
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(owner_bytes(&state, user).await, 0);
}

#[tokio::test]
#[ignore = "requires disposable payload fixture 55449/56449"]
async fn payload_identity_event_fanout_is_charged_and_rejected_atomically() {
    let state = state().await;
    let app = crate::app(state.clone());
    let (a, a_user, _) = account(&app).await;
    let (b, b_user, _) = account(&app).await;
    assert_eq!(
        request(
            &app,
            "POST",
            "/chats/direct",
            &a,
            json!({"other_user_id":b_user})
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        request(&app, "POST", "/keys", &a, keys(1, 0, 0)).await.0,
        StatusCode::OK
    );
    let before = owner_bytes(&state, a_user).await;
    assert_eq!(
        request(&app, "POST", "/keys", &a, keys(2, 0, 0)).await.0,
        StatusCode::OK
    );
    let after = owner_bytes(&state, a_user).await;
    assert!(
        after > before,
        "fixed-size identity rotation must charge its new partner event"
    );
    let old: i64 =
        sqlx::query_scalar("SELECT database_user_quota_bytes FROM server_policy WHERE id=true")
            .fetch_one(&state.db)
            .await
            .unwrap();
    sqlx::query("UPDATE server_policy SET database_user_quota_bytes=$1 WHERE id=true")
        .bind(after)
        .execute(&state.db)
        .await
        .unwrap();
    let events: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM security_events WHERE subject_user_id=$1")
            .bind(a_user)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(
        request(&app, "POST", "/keys", &a, keys(3, 0, 0)).await.0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(owner_bytes(&state, a_user).await, after);
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM security_events WHERE subject_user_id=$1"
        )
        .bind(a_user)
        .fetch_one(&state.db)
        .await
        .unwrap(),
        events
    );
    sqlx::query("UPDATE server_policy SET database_user_quota_bytes=$1 WHERE id=true")
        .bind(old)
        .execute(&state.db)
        .await
        .unwrap();
    for token in [&a, &b] {
        assert_eq!(
            request(
                &app,
                "POST",
                "/users/me/delete",
                token,
                json!({"password":"fixture-password-123"})
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
    }
}

#[tokio::test]
#[ignore = "requires disposable payload fixture 55449/56449"]
async fn payload_replenishment_and_account_delete_do_not_invert_global_lock() {
    let state = state().await;
    let app = crate::app(state.clone());
    let (token, user, device) = account(&app).await;
    let chat = saved(&app, &token).await;
    assert_eq!(
        request(
            &app,
            "POST",
            &format!("/chats/{chat}/messages"),
            &token,
            content(Uuid::new_v4())
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        request(&app, "POST", "/keys", &token, keys(1, 0, 20))
            .await
            .0,
        StatusCode::OK
    );
    let mut upload = state.db.begin().await.unwrap();
    sqlx::query("INSERT INTO one_time_prekeys(device_id,key_id,public_key) VALUES($1,9999,$2)")
        .bind(device)
        .bind(vec![7_u8; 33])
        .execute(&mut *upload)
        .await
        .unwrap();
    reserve_device_keys(&mut upload, device, user)
        .await
        .unwrap();
    let mut deletion = state.db.begin().await.unwrap();
    sqlx::query("DELETE FROM messages WHERE chat_id=$1")
        .bind(chat)
        .execute(&mut *deletion)
        .await
        .unwrap();
    let waiting = tokio::spawn(async move {
        sqlx::query("DELETE FROM users WHERE id=$1")
            .bind(user)
            .execute(&mut *deletion)
            .await
            .unwrap();
        deletion.commit().await.unwrap();
    });
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    tokio::time::timeout(std::time::Duration::from_secs(5), upload.commit())
        .await
        .expect("upload commit must not wait for deletion's accounting lock")
        .unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(5), waiting)
        .await
        .expect("account cascade must finish after upload commits")
        .unwrap();
    assert_eq!(owner_bytes(&state, user).await, 0);
}

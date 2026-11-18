//! Real isolated PG/Redis tests for the schema54 admission/key-mailbox boundary.
use super::*;
use crate::conferences::discovery::{
    cancel_key, directory, fulfill, provider_poll, read_key, request_key, set_create_pause,
    DirectoryQuery, Envelope, Fulfill, KeyCreate, KeyCreatePause, KeyRequest,
};
use axum::extract::Query;
use base64::engine::general_purpose::STANDARD;
use serde_json::json;

fn public_request(access: &str, password: Option<&str>) -> CreateRequest {
    CreateRequest {
        listed: Some(true),
        access_mode: Some(access.into()),
        password: password.map(str::to_owned),
        ..create_request(5)
    }
}
fn provider_join(password: Option<&str>) -> JoinRequest {
    JoinRequest {
        supports_key_handoff: true,
        password: password.map(str::to_owned),
        ..join_request()
    }
}
fn key_create(password: Option<&str>) -> KeyCreate {
    KeyCreate {
        request_id: Uuid::new_v4(),
        nonce: URL_SAFE_NO_PAD.encode(Uuid::new_v4().as_bytes().repeat(2)),
        password: password.map(str::to_owned),
    }
}
fn fulfill_body(request: &KeyRequest, size: usize) -> Fulfill {
    Fulfill {
        assignment_revision: request.assignment_revision,
        access_revision: request.access_revision,
        envelope: Envelope {
            recipient_device_id: request.requester_device_id,
            message_type: 1,
            ciphertext: STANDARD.encode(vec![7u8; size]),
        },
    }
}
async fn http(
    state: &AppState,
    auth: &AuthUser,
    method: &str,
    path: &str,
    body: serde_json::Value,
) -> axum::response::Response {
    let access = crate::auth::jwt::issue_access_token(
        &state.config,
        auth.user_id,
        auth.device_id,
        auth.session_id,
    )
    .unwrap();
    crate::app(state.clone())
        .oneshot(
            axum::http::Request::builder()
                .method(method)
                .uri(path)
                .extension(axum::extract::ConnectInfo(std::net::SocketAddr::from((
                    [127, 0, 0, 2],
                    12345,
                ))))
                .header("authorization", format!("Bearer {}", access.token))
                .header("content-type", "application/json")
                .body(axum::body::Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap()
}
async fn list_json(state: &AppState, auth: &AuthUser, query: DirectoryQuery) -> serde_json::Value {
    serde_json::to_value(
        directory(State(state.clone()), auth.clone(), Query(query))
            .await
            .unwrap()
            .0,
    )
    .unwrap()
}

#[tokio::test]
#[ignore = "dedicated disposable conference_test PG55439/Redis56439 only"]
async fn isolated_discovery_modes_password_legacy_and_live_recovery() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let room = create(State(state.clone()), owner.clone(), Json(create_request(5)))
        .await
        .unwrap()
        .0;
    assert!(!room.listed && room.access_mode == "invite" && room.description.is_empty());
    assert_eq!(room.settings_revision, 1);
    let owner_grant = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(provider_join(None)),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        list_json(&state, &member, DirectoryQuery::default()).await["items"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
    let edited = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(UpdateSettingsRequest {
            listed: Some(true),
            access_mode: Some("invite_password".into()),
            password_action: Some("set".into()),
            password: Some(" secret ".into()),
            expected_revision: Some(1),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(edited.settings_revision, 2);
    assert_eq!(edited.access_revision, 2);
    assert!(
        matches!(join(State(state.clone()),member.clone(),Path(room.id),Json(join_request())).await,Err(AppError::Forbidden(message)) if message=="Conference password is required.")
    );
    assert!(
        matches!(join(State(state.clone()),member.clone(),Path(room.id),Json(provider_join(Some("secret")))).await,Err(AppError::Forbidden(message)) if message=="Conference password is incorrect.")
    );
    let admission = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(provider_join(Some(" secret "))),
    )
    .await
    .unwrap()
    .0;
    let refreshed = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(join_request()),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        refreshed.participant_identity, owner_grant.participant_identity,
        "existing owner must not need changed password"
    );
    assert_eq!(
        list_json(&state, &member, DirectoryQuery::default()).await["items"][0]["access_mode"],
        "invite_password"
    );
    let bad = http(
        &state,
        &owner,
        "PATCH",
        &format!("/conferences/{}/settings", room.id),
        json!({"expected_revision":1,"listed":false}),
    )
    .await;
    assert_eq!(bad.status(), StatusCode::CONFLICT);
    let bad: serde_json::Value =
        serde_json::from_slice(&bad.into_body().collect().await.unwrap().to_bytes()).unwrap();
    assert_eq!(bad["error"]["code"], "settings_changed");
    let new = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(UpdateSettingsRequest {
            expected_revision: Some(2),
            access_mode: Some("open".into()),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(new.access_revision, 3);
    let hash: Option<String> =
        sqlx::query_scalar("SELECT password_hash FROM conferences WHERE id=$1")
            .bind(room.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert!(hash.is_none());
    assert_eq!(
        join(
            State(state.clone()),
            member.clone(),
            Path(room.id),
            Json(join_request())
        )
        .await
        .unwrap()
        .0
        .participant_identity,
        admission.participant_identity
    );
    assert_eq!(
        authorize_sfu(
            State(state.clone()),
            gate_headers(&admission.participant_token)
        )
        .await
        .unwrap(),
        StatusCode::NO_CONTENT
    );
    assert!(matches!(
        request_key(
            State(state.clone()),
            member.clone(),
            Path(room.id),
            Json(key_create(None))
        )
        .await,
        Ok(_)
    ));
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "dedicated disposable conference_test PG55439/Redis56439 only"]
async fn isolated_catalog_exact_device_ready_consumption_and_no_invite_secret() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let room = create(
        State(state.clone()),
        owner.clone(),
        Json(public_request("password", Some("pass phrase"))),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(provider_join(Some("pass phrase"))),
    )
    .await
    .unwrap();
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    let create_body = key_create(Some("pass phrase"));
    let request_id = create_body.request_id;
    let nonce = create_body.nonce.clone();
    let request = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(create_body),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(request.provider_user_id, Some(owner.user_id));
    assert_eq!(request.assignment_revision, 1);
    let replay = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(KeyCreate {
            request_id,
            nonce: nonce.clone(),
            password: Some("pass phrase".into()),
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(replay.id, request.id);
    assert!(matches!(
        request_key(
            State(state.clone()),
            member.clone(),
            Path(room.id),
            Json(KeyCreate {
                request_id,
                nonce,
                password: Some("different".into())
            })
        )
        .await,
        Err(AppError::Forbidden(message)) if message=="Conference password is incorrect."
    ));
    let stranger = fixture_user(&state.db).await;
    assert!(matches!(
        read_key(
            State(state.clone()),
            stranger.clone(),
            Path((room.id, request.id))
        )
        .await,
        Err(AppError::NotFound(_))
    ));
    let unrelated = AuthUser {
        session_id: Uuid::new_v4(),
        ..member.clone()
    };
    fixture_session(&state.db, &unrelated).await;
    assert!(matches!(
        read_key(State(state.clone()), unrelated, Path((room.id, request.id))).await,
        Err(AppError::NotFound(_))
    ));
    let mut invalid = fulfill_body(&request, 32);
    invalid.envelope.recipient_device_id = stranger.device_id;
    assert!(matches!(
        fulfill(
            State(state.clone()),
            owner.clone(),
            Path((room.id, request.id)),
            Json(invalid)
        )
        .await,
        Err(AppError::Validation(_))
    ));
    let valid = fulfill_body(&request, 8192);
    let response=http(&state,&owner,"POST",&format!("/conferences/{}/key-requests/{}/fulfill",room.id,request.id),json!({"assignment_revision":valid.assignment_revision,"access_revision":valid.access_revision,"envelope":valid.envelope})).await;
    assert_eq!(
        response.status(),
        StatusCode::OK,
        "only the fulfillment route must accept >2KiB envelope"
    );
    assert_eq!(
        http(
            &state,
            &member,
            "POST",
            &format!("/conferences/{}/join", room.id),
            json!({"invite_secret":"x".repeat(3000)})
        )
        .await
        .status(),
        StatusCode::PAYLOAD_TOO_LARGE
    );
    let ready = read_key(
        State(state.clone()),
        member.clone(),
        Path((room.id, request.id)),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(ready.status, "ready");
    assert_eq!(
        ready.envelope.as_ref().unwrap().recipient_device_id,
        member.device_id
    );
    let wire = serde_json::to_value(&ready).unwrap();
    assert!(wire.get("invite_secret").is_none() && wire.get("media_key").is_none());
    let grant = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(request.id),
            supports_key_handoff: true,
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(grant.conference.participants.len(), 2);
    let repeat = fulfill(
        State(state.clone()),
        owner.clone(),
        Path((room.id, request.id)),
        Json(fulfill_body(&request, 8192)),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(repeat.status, "consumed");
    sqlx::query(
        "UPDATE conference_key_requests SET expires_at=now()-interval '1 second' WHERE id=$1",
    )
    .bind(request.id)
    .execute(&state.db)
    .await
    .unwrap();
    let new = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(UpdateSettingsRequest {
            expected_revision: Some(1),
            access_mode: Some("invite_password".into()),
            password_action: Some("set".into()),
            password: Some("changed-pass".into()),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(new.access_revision, 2);
    let retry = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(request.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        retry.participant_identity, grant.participant_identity,
        "existing catalog member reconnect survives expiry/password change"
    );
    let _ = leave(State(state.clone()), member.clone(), Path(room.id))
        .await
        .unwrap();
    assert!(
        join(
            State(state.clone()),
            member.clone(),
            Path(room.id),
            Json(JoinRequest {
                key_request_id: Some(request.id),
                ..Default::default()
            })
        )
        .await
        .is_err(),
        "Leave must invalidate consumed request"
    );
    assert!(
        matches!(
            join(
                State(state.clone()),
                member.clone(),
                Path(room.id),
                Json(join_request())
            )
            .await,
            Err(AppError::Forbidden(_))
        ),
        "legacy /join cannot bypass new password"
    );
    let _ = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(UpdateSettingsRequest {
            expected_revision: Some(2),
            access_mode: Some("open".into()),
            ..Default::default()
        }),
    )
    .await
    .unwrap();
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    let second = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(key_create(None)),
    )
    .await
    .unwrap()
    .0;
    let _ = fulfill(
        State(state.clone()),
        owner.clone(),
        Path((room.id, second.id)),
        Json(fulfill_body(&second, 32)),
    )
    .await
    .unwrap();
    let second_grant = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(second.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert!(
        matches!(
            join(
                State(state.clone()),
                member.clone(),
                Path(room.id),
                Json(JoinRequest {
                    key_request_id: Some(request.id),
                    ..Default::default()
                })
            )
            .await,
            Err(AppError::Gone(_))
        ),
        "old consumed A must not authorize an existing new B admission"
    );
    sqlx::query("DELETE FROM conference_key_requests WHERE id=$1")
        .bind(second.id)
        .execute(&state.db)
        .await
        .unwrap();
    let _ = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(UpdateSettingsRequest {
            expected_revision: Some(3),
            access_mode: Some("invite".into()),
            ..Default::default()
        }),
    )
    .await
    .unwrap();
    let second_retry = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(second.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        second_grant.participant_identity, second_retry.participant_identity,
        "current catalog B refresh survives request cleanup/access change"
    );
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "dedicated disposable conference_test PG55439/Redis56439 only"]
async fn isolated_key_provider_generation_reassignment_expiry_and_cancel() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let provider = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let room = create(
        State(state.clone()),
        owner.clone(),
        Json(public_request("open", None)),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(provider_join(None)),
    )
    .await
    .unwrap();
    let _ = join(
        State(state.clone()),
        provider.clone(),
        Path(room.id),
        Json(provider_join(None)),
    )
    .await
    .unwrap();
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    let _ = provider_poll(State(state.clone()), provider.clone(), Path(room.id))
        .await
        .unwrap();
    let request = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(key_create(None)),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(request.provider_user_id, Some(owner.user_id));
    sqlx::query("UPDATE conference_members SET key_service_seen_at=now()-interval '16 seconds' WHERE user_id=$1").bind(owner.user_id).execute(&state.db).await.unwrap();
    let reassigned = read_key(
        State(state.clone()),
        member.clone(),
        Path((room.id, request.id)),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(reassigned.provider_user_id, Some(provider.user_id));
    assert_eq!(reassigned.assignment_revision, 2);
    assert!(fulfill(
        State(state.clone()),
        owner.clone(),
        Path((room.id, request.id)),
        Json(fulfill_body(&request, 32))
    )
    .await
    .is_err());
    let _ = fulfill(
        State(state.clone()),
        provider.clone(),
        Path((room.id, request.id)),
        Json(fulfill_body(&reassigned, 32)),
    )
    .await
    .unwrap();
    let _ = cancel_key(
        State(state.clone()),
        member.clone(),
        Path((room.id, request.id)),
    )
    .await
    .unwrap();
    assert_eq!(
        read_key(
            State(state.clone()),
            member.clone(),
            Path((room.id, request.id))
        )
        .await
        .unwrap()
        .0
        .status,
        "cancelled"
    );
    assert!(fulfill(
        State(state.clone()),
        provider.clone(),
        Path((room.id, request.id)),
        Json(fulfill_body(&reassigned, 32))
    )
    .await
    .is_err());
    let pending = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(key_create(None)),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(pending.provider_user_id, Some(provider.user_id));
    let _ = leave(State(state.clone()), provider.clone(), Path(room.id))
        .await
        .unwrap();
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    let after_leave = read_key(
        State(state.clone()),
        member.clone(),
        Path((room.id, pending.id)),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(after_leave.provider_user_id, Some(owner.user_id));
    assert!(after_leave.assignment_revision > pending.assignment_revision);
    assert!(
        fulfill(
            State(state.clone()),
            provider.clone(),
            Path((room.id, pending.id)),
            Json(fulfill_body(&pending, 32))
        )
        .await
        .is_err(),
        "departed provider admission must not fulfill an assigned request"
    );
    sqlx::query(
        "UPDATE conference_key_requests SET expires_at=now()-interval '1 second' WHERE id=$1",
    )
    .bind(pending.id)
    .execute(&state.db)
    .await
    .unwrap();
    assert_eq!(
        read_key(
            State(state.clone()),
            member.clone(),
            Path((room.id, pending.id))
        )
        .await
        .unwrap()
        .0
        .status,
        "expired"
    );
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM conference_members WHERE conference_id=$1")
            .bind(room.id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(
        count, 1,
        "waiting requests must not reserve capacity after provider departure"
    );
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "dedicated disposable conference_test PG55439/Redis56439 only"]
async fn isolated_directory_pagination_blocks_modes_and_key_request_revocation() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let viewer = fixture_user(&state.db).await;
    let one = create(
        State(state.clone()),
        owner.clone(),
        Json(public_request("open", None)),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(one.id),
        Json(provider_join(None)),
    )
    .await
    .unwrap();
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(one.id))
        .await
        .unwrap();
    let two = create(
        State(state.clone()),
        owner.clone(),
        Json(public_request("invite", None)),
    )
    .await
    .unwrap()
    .0;
    let first = list_json(
        &state,
        &viewer,
        DirectoryQuery {
            limit: Some(1),
            ..Default::default()
        },
    )
    .await;
    assert_eq!(first["items"].as_array().unwrap().len(), 1);
    let cursor = first["next_cursor"].as_str().unwrap().to_owned();
    let second = list_json(
        &state,
        &viewer,
        DirectoryQuery {
            cursor: Some(cursor.clone()),
            limit: Some(1),
            ..Default::default()
        },
    )
    .await;
    assert_ne!(first["items"][0]["id"], second["items"][0]["id"]);
    assert!(directory(
        State(state.clone()),
        viewer.clone(),
        Query(DirectoryQuery {
            q: Some("different".into()),
            cursor: Some(cursor),
            ..Default::default()
        })
    )
    .await
    .is_err());
    assert!(
        list_json(
            &state,
            &viewer,
            DirectoryQuery {
                q: Some("%".into()),
                ..Default::default()
            }
        )
        .await["items"]
            .as_array()
            .unwrap()
            .is_empty(),
        "LIKE wildcard must be escaped"
    );
    assert!(matches!(
        request_key(
            State(state.clone()),
            viewer.clone(),
            Path(two.id),
            Json(key_create(None))
        )
        .await,
        Err(AppError::NotFound(_))
    ));
    let pending = request_key(
        State(state.clone()),
        viewer.clone(),
        Path(one.id),
        Json(key_create(None)),
    )
    .await
    .unwrap()
    .0;
    let _ = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(one.id),
        Json(UpdateSettingsRequest {
            expected_revision: Some(1),
            admission_locked: Some(true),
            ..Default::default()
        }),
    )
    .await
    .unwrap();
    assert_eq!(
        read_key(
            State(state.clone()),
            viewer.clone(),
            Path((one.id, pending.id))
        )
        .await
        .unwrap()
        .0
        .status,
        "revoked"
    );
    sqlx::query("INSERT INTO user_blocks(user_id,blocked_user_id) VALUES($1,$2)")
        .bind(viewer.user_id)
        .bind(owner.user_id)
        .execute(&state.db)
        .await
        .unwrap();
    assert!(
        list_json(&state, &viewer, DirectoryQuery::default()).await["items"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    let _ = end(State(state.clone()), owner.clone(), Path(one.id))
        .await
        .unwrap();
    let _ = end(State(state.clone()), owner, Path(two.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "dedicated disposable conference_test PG55439/Redis56439 only"]
async fn isolated_target_bundle_consumes_only_requested_device_prekey() {
    let (state, _, fixture) = isolated_fixture().await;
    let requester = fixture_user(&state.db).await;
    let target = fixture_user(&state.db).await;
    let other = Uuid::new_v4();
    sqlx::query("INSERT INTO devices(id,user_id,platform,signal_device_id,installation_id) VALUES($1,$2,'ios',2,$3)").bind(other).bind(target.user_id).bind(Uuid::new_v4()).execute(&state.db).await.unwrap();
    for device in [target.device_id, other] {
        sqlx::query("INSERT INTO device_identity_keys(device_id,identity_key,registration_id) VALUES($1,$2,1)").bind(device).bind(vec![7u8;33]).execute(&state.db).await.unwrap();
        sqlx::query(
            "INSERT INTO signed_prekeys(device_id,key_id,public_key,signature) VALUES($1,1,$2,$2)",
        )
        .bind(device)
        .bind(vec![7u8; 33])
        .execute(&state.db)
        .await
        .unwrap();
        sqlx::query(
            "INSERT INTO kyber_prekeys(device_id,key_id,public_key,signature) VALUES($1,1,$2,$2)",
        )
        .bind(device)
        .bind(vec![7u8; 33])
        .execute(&state.db)
        .await
        .unwrap();
        sqlx::query("INSERT INTO one_time_prekeys(device_id,key_id,public_key) VALUES($1,1,$2)")
            .bind(device)
            .bind(vec![7u8; 33])
            .execute(&state.db)
            .await
            .unwrap();
    }
    let response = http(
        &state,
        &requester,
        "GET",
        &format!("/keys/bundles/{}/{}", target.user_id, target.device_id),
        json!(null),
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let body: serde_json::Value =
        serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
    assert_eq!(body["devices"].as_array().unwrap().len(), 1);
    assert_eq!(
        body["devices"][0]["device_id"],
        target.device_id.to_string()
    );
    assert!(body.get("user_id").is_none());
    for (device, unused) in [(target.device_id, 0i64), (other, 1i64)] {
        let actual: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM one_time_prekeys WHERE device_id=$1 AND used_at IS NULL",
        )
        .bind(device)
        .fetch_one(&state.db)
        .await
        .unwrap();
        assert_eq!(actual, unused);
    }
    assert_eq!(
        http(
            &state,
            &requester,
            "GET",
            &format!("/keys/bundles/{}/{}", target.user_id, Uuid::new_v4()),
            json!(null)
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );
    fixture.abort();
}

#[tokio::test]
#[ignore = "dedicated disposable conference_test PG55439/Redis56439 only"]
async fn isolated_fresh_ready_rebind_recovers_ram_loss_without_replaying_old_credentials() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let room = create(
        State(state.clone()),
        owner.clone(),
        Json(public_request("open", None)),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(provider_join(None)),
    )
    .await
    .unwrap();
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    let first = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(key_create(None)),
    )
    .await
    .unwrap()
    .0;
    let _ = fulfill(
        State(state.clone()),
        owner.clone(),
        Path((room.id, first.id)),
        Json(fulfill_body(&first, 32)),
    )
    .await
    .unwrap();
    let first_grant = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(first.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    // After process RAM loss, a fresh mailbox B is an independent authorization
    // proof. It replaces A atomically while retaining the exact admission.
    let second = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(key_create(None)),
    )
    .await
    .unwrap()
    .0;
    let _ = fulfill(
        State(state.clone()),
        owner.clone(),
        Path((room.id, second.id)),
        Json(fulfill_body(&second, 32)),
    )
    .await
    .unwrap();
    let second_grant = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(second.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        first_grant.participant_identity,
        second_grant.participant_identity
    );
    assert!(
        matches!(
            join(
                State(state.clone()),
                member.clone(),
                Path(room.id),
                Json(JoinRequest {
                    key_request_id: Some(first.id),
                    ..Default::default()
                })
            )
            .await,
            Err(AppError::Gone(_))
        ),
        "consumed A may not refresh the rebound B admission"
    );
    let bound: Option<Uuid> =
        sqlx::query_scalar("SELECT catalog_request_id FROM conference_members WHERE user_id=$1")
            .bind(member.user_id)
            .fetch_one(&state.db)
            .await
            .unwrap();
    assert_eq!(bound, Some(second.id));
    sqlx::query("DELETE FROM conference_key_requests WHERE id=$1")
        .bind(second.id)
        .execute(&state.db)
        .await
        .unwrap();
    let _ = update_settings(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(UpdateSettingsRequest {
            expected_revision: Some(1),
            access_mode: Some("invite_password".into()),
            password_action: Some("set".into()),
            password: Some("new password".into()),
            ..Default::default()
        }),
    )
    .await
    .unwrap();
    let refreshed = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(second.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        refreshed.participant_identity,
        second_grant.participant_identity
    );
    let _ = leave(State(state.clone()), member.clone(), Path(room.id))
        .await
        .unwrap();
    for old in [first.id, second.id] {
        assert!(
            join(
                State(state.clone()),
                member.clone(),
                Path(room.id),
                Json(JoinRequest {
                    key_request_id: Some(old),
                    ..Default::default()
                })
            )
            .await
            .is_err(),
            "Leave must revoke both old catalog credentials"
        );
    }
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "dedicated disposable conference_test PG55439/Redis56439 only"]
async fn isolated_cancel_before_insert_is_durable_scoped_and_cannot_resurrect_after_deadline() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let stranger = fixture_user(&state.db).await;
    let room = create(
        State(state.clone()),
        owner.clone(),
        Json(public_request("password", Some("fixture-password"))),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(provider_join(Some("fixture-password"))),
    )
    .await
    .unwrap();
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    let a = key_create(Some("fixture-password"));
    let a_id = a.request_id;
    let a_nonce = a.nonce.clone();
    let pause = Arc::new(KeyCreatePause {
        request: a_id,
        entered: tokio::sync::Notify::new(),
        resume: tokio::sync::Notify::new(),
    });
    set_create_pause(Some(pause.clone()));
    let post = tokio::spawn(request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(a),
    ));
    tokio::time::timeout(std::time::Duration::from_secs(5), pause.entered.notified())
        .await
        .expect("POST must release room lock and reach password KDF barrier");
    let cancelled = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        cancel_key(State(state.clone()), member.clone(), Path((room.id, a_id))),
    )
    .await;
    pause.resume.notify_one();
    let posted = tokio::time::timeout(std::time::Duration::from_secs(5), post)
        .await
        .unwrap()
        .unwrap();
    set_create_pause(None);
    assert!(
        cancelled.unwrap().is_ok(),
        "DELETE must succeed while no request row exists"
    );
    assert!(
        matches!(posted,Err(AppError::Gone(message)) if message=="Conference key request was cancelled.")
    );
    let rows: i64 = sqlx::query_scalar("SELECT count(*) FROM conference_key_requests WHERE id=$1")
        .bind(a_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(rows, 0, "No synthetic nonce/request and no late INSERT A");
    let _ = cancel_key(State(state.clone()), member.clone(), Path((room.id, a_id)))
        .await
        .unwrap();
    assert!(matches!(
        request_key(
            State(state.clone()),
            member.clone(),
            Path(room.id),
            Json(KeyCreate {
                request_id: a_id,
                nonce: a_nonce,
                password: Some("fixture-password".into())
            })
        )
        .await,
        Err(AppError::Gone(_))
    ));

    let b = key_create(Some("fixture-password"));
    let b_id = b.request_id;
    let other_device = AuthUser {
        device_id: Uuid::new_v4(),
        session_id: Uuid::new_v4(),
        ..member.clone()
    };
    sqlx::query("INSERT INTO devices(id,user_id,platform,signal_device_id,installation_id) VALUES($1,$2,'ios',2,$3)").bind(other_device.device_id).bind(member.user_id).bind(Uuid::new_v4()).execute(&state.db).await.unwrap();
    fixture_session(&state.db, &other_device).await;
    let other_family = AuthUser {
        session_id: Uuid::new_v4(),
        ..member.clone()
    };
    fixture_session(&state.db, &other_family).await;
    // An absent-request cancellation belongs only to the authenticated namespace.
    for attacker in [other_device, other_family, stranger.clone()] {
        let _ = cancel_key(State(state.clone()), attacker, Path((room.id, b_id)))
            .await
            .unwrap();
    }
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    let b = request_key(State(state.clone()), member.clone(), Path(room.id), Json(b))
        .await
        .unwrap()
        .0;
    assert_eq!(
        b.status, "pending",
        "fresh B must not wait for A TTL or foreign cancellation"
    );
    assert!(matches!(
        cancel_key(State(state.clone()), stranger, Path((room.id, b_id))).await,
        Err(AppError::NotFound(_))
    ));
    let _ = fulfill(
        State(state.clone()),
        owner.clone(),
        Path((room.id, b.id)),
        Json(fulfill_body(&b, 32)),
    )
    .await
    .unwrap();
    let grant = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(b.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    let _ = cancel_key(State(state.clone()), member.clone(), Path((room.id, b.id)))
        .await
        .unwrap();
    let same = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(b.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        same.participant_identity, grant.participant_identity,
        "DELETE consumed is not Leave"
    );
    assert_eq!(
        authorize_sfu(State(state.clone()), gate_headers(&grant.participant_token))
            .await
            .unwrap(),
        StatusCode::NO_CONTENT
    );
    let own_cancel:i64=sqlx::query_scalar("SELECT count(*) FROM conference_key_request_cancellations WHERE conference_id=$1 AND request_id=$2 AND requester_session_id=$3").bind(room.id).bind(b.id).bind(member.session_id).fetch_one(&state.db).await.unwrap();
    assert_eq!(own_cancel, 0, "consumed DELETE is an actual no-op");

    // Model a CPU-stalled old handler after a tombstone was pruned: elapsed
    // server time, not a caller-supplied timestamp, must still prevent INSERT.
    let c = key_create(Some("fixture-password"));
    let c_id = c.request_id;
    let pause = Arc::new(KeyCreatePause {
        request: c_id,
        entered: tokio::sync::Notify::new(),
        resume: tokio::sync::Notify::new(),
    });
    set_create_pause(Some(pause.clone()));
    let post = tokio::spawn(request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(c),
    ));
    tokio::time::timeout(std::time::Duration::from_secs(5), pause.entered.notified())
        .await
        .unwrap();
    let _ = cancel_key(State(state.clone()), member.clone(), Path((room.id, c_id)))
        .await
        .unwrap();
    sqlx::query("UPDATE conference_key_request_cancellations SET expires_at=now()-interval '1 second' WHERE conference_id=$1 AND request_id=$2").bind(room.id).bind(c_id).execute(&state.db).await.unwrap();
    crate::conferences::discovery::cleanup(&state)
        .await
        .unwrap();
    tokio::time::sleep(std::time::Duration::from_secs(61)).await;
    pause.resume.notify_one();
    let posted = tokio::time::timeout(std::time::Duration::from_secs(5), post)
        .await
        .unwrap()
        .unwrap();
    set_create_pause(None);
    assert!(
        matches!(posted,Err(AppError::Gone(message)) if message=="Conference key request creation expired.")
    );
    let rows: i64 = sqlx::query_scalar("SELECT count(*) FROM conference_key_requests WHERE id=$1")
        .bind(c_id)
        .fetch_one(&state.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

#[tokio::test]
#[ignore = "dedicated disposable conference_test PG55439/Redis56439 only"]
async fn isolated_authorized_new_intent_supersedes_only_own_unfinished_same_family_request() {
    let (state, _, fixture) = isolated_fixture().await;
    let owner = fixture_user(&state.db).await;
    let member = fixture_user(&state.db).await;
    let room = create(
        State(state.clone()),
        owner.clone(),
        Json(public_request("password", Some("fixture-password"))),
    )
    .await
    .unwrap()
    .0;
    let _ = join(
        State(state.clone()),
        owner.clone(),
        Path(room.id),
        Json(provider_join(Some("fixture-password"))),
    )
    .await
    .unwrap();
    let _ = provider_poll(State(state.clone()), owner.clone(), Path(room.id))
        .await
        .unwrap();
    let a = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(key_create(Some("fixture-password"))),
    )
    .await
    .unwrap()
    .0;
    let b_id = Uuid::new_v4();
    let b_nonce = URL_SAFE_NO_PAD.encode([6u8; 32]);
    assert!(matches!(
        request_key(
            State(state.clone()),
            member.clone(),
            Path(room.id),
            Json(KeyCreate {
                request_id: b_id,
                nonce: b_nonce.clone(),
                password: Some("incorrect-password".into())
            })
        )
        .await,
        Err(AppError::Forbidden(_))
    ));
    assert_eq!(
        read_key(State(state.clone()), member.clone(), Path((room.id, a.id)))
            .await
            .unwrap()
            .0
            .status,
        "pending",
        "wrong password cannot revoke A"
    );
    let b = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(KeyCreate {
            request_id: b_id,
            nonce: b_nonce,
            password: Some("fixture-password".into()),
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(
        read_key(State(state.clone()), member.clone(), Path((room.id, a.id)))
            .await
            .unwrap()
            .0
            .status,
        "revoked"
    );
    let _ = fulfill(
        State(state.clone()),
        owner.clone(),
        Path((room.id, b.id)),
        Json(fulfill_body(&b, 32)),
    )
    .await
    .unwrap();
    let c = request_key(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(key_create(Some("fixture-password"))),
    )
    .await
    .unwrap()
    .0;
    let b_state = read_key(State(state.clone()), member.clone(), Path((room.id, b.id)))
        .await
        .unwrap()
        .0;
    assert_eq!(b_state.status, "revoked");
    assert!(b_state.envelope.is_none());
    for stale in [a.id, b.id] {
        assert!(matches!(
            join(
                State(state.clone()),
                member.clone(),
                Path(room.id),
                Json(JoinRequest {
                    key_request_id: Some(stale),
                    ..Default::default()
                })
            )
            .await,
            Err(AppError::Gone(_))
        ));
    }
    let _ = fulfill(
        State(state.clone()),
        owner.clone(),
        Path((room.id, c.id)),
        Json(fulfill_body(&c, 32)),
    )
    .await
    .unwrap();
    let grant = join(
        State(state.clone()),
        member.clone(),
        Path(room.id),
        Json(JoinRequest {
            key_request_id: Some(c.id),
            ..Default::default()
        }),
    )
    .await
    .unwrap()
    .0;
    assert_eq!(grant.conference.participants.len(), 2);
    let live:i64=sqlx::query_scalar("SELECT count(*) FROM conference_key_requests WHERE conference_id=$1 AND status IN ('pending','ready')").bind(room.id).fetch_one(&state.db).await.unwrap();
    assert_eq!(live, 0);
    let _ = end(State(state.clone()), owner, Path(room.id))
        .await
        .unwrap();
    fixture.abort();
}

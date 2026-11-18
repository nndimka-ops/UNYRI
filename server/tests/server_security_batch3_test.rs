//! Batch 3a regressions. Explicit disposable loopback DB/Redis only; no real
//! storage, push provider, TURN, or application-server connection is used.
use axum::{body::Body, extract::ConnectInfo, http::{Request, StatusCode}};
use http_body_util::BodyExt;
use messenger_server::{auth::{jwt, middleware::AuthUser}, config::{Config, S3Config}, state::AppState};
use serde_json::{json, Value};
use tower::ServiceExt;
use uuid::Uuid;
use std::{net::SocketAddr, sync::Arc};

async fn fixture() -> (AppState, axum::Router) {
    let database_url = std::env::var("TEST_DATABASE_URL").expect("explicit disposable server_sec3_test DB required");
    let redis_url = std::env::var("TEST_REDIS_URL").expect("explicit disposable Redis required");
    for raw in [&database_url, &redis_url] {
        let url = reqwest::Url::parse(raw).unwrap();
        assert!(matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]")));
    }
    assert_eq!(reqwest::Url::parse(&database_url).unwrap().path(), "/server_sec3_test");
    let redis_db: usize = reqwest::Url::parse(&redis_url).unwrap().path().trim_start_matches('/')
        .parse().expect("explicit nonzero disposable Redis DB required");
    assert!(redis_db > 0, "default Redis DB is not a disposable fixture");
    let db = sqlx::postgres::PgPoolOptions::new().max_connections(8).connect(&database_url).await.unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    let redis = redis::Client::open(redis_url.clone()).unwrap().get_connection_manager().await.unwrap();
    let config = Config {
        identity: Default::default(), host:"127.0.0.1".into(),port:0,database_url,database_max_connections:8,
        redis_url,jwt_secret:"batch3-only-test-secret".into(),trusted_proxies:vec![],jwt_issuer:"batch3".into(),
        jwt_audience:"batch3-test".into(),access_token_ttl_seconds:900,refresh_token_ttl_seconds:86400,
        s3:S3Config { endpoint_url:"http://127.0.0.1:59000".into(),public_endpoint_url:"http://127.0.0.1:59000".into(),
            region:"test".into(),bucket:"batch3-test".into(),access_key_id:"test".into(),secret_access_key:"test".into(),
            upload_access_key_id:"test".into(),upload_secret_access_key:"test".into(),max_upload_duration_seconds:60,presign_ttl_seconds:600 },
        turn:messenger_server::config::TurnConfig { stun_urls:vec![],turn_urls:vec![],shared_secret:"test".into(),credential_ttl_seconds:600 },fcm:Default::default(),
    };
    let s3=Arc::new(messenger_server::storage::S3Client::new(config.s3.clone()));
    let state=AppState { resources:Default::default(),db,redis,config,ws_registry:Default::default(),s3,push:messenger_server::push::disabled() };
    let app=messenger_server::app(state.clone()); (state,app)
}

fn request(method:&str,path:&str,token:&str,body:Value)->Request<Body> {
    let octets: [u8;3]=std::array::from_fn(|_|rand::random());
    Request::builder().method(method).uri(path).header("content-type","application/json")
        .header("authorization",format!("Bearer {token}"))
        // In-process fixture identities avoid cross-test auth-network buckets;
        // every actual listener/DB/Redis connection remains loopback.
        .extension(ConnectInfo(SocketAddr::from(([10,octets[0],octets[1],octets[2]],12345))))
        .body(Body::from(body.to_string())).unwrap()
}
async fn send(app:&axum::Router, method:&str,path:&str,token:&str,body:Value)->(StatusCode,Value) {
    let response=app.clone().oneshot(request(method,path,token,body)).await.unwrap();
    let status=response.status();let bytes=response.into_body().collect().await.unwrap().to_bytes();
    (status,serde_json::from_slice(&bytes).unwrap_or(Value::Null))
}
struct User { token:String,refresh:String,username:String,auth:AuthUser,installation:Uuid }
async fn user(state:&AppState,app:&axum::Router)->User {
    // Each fixture has a fresh IP so the full suite stays below per-IP sign-up limits.
    let username=format!("sec3_{}",&Uuid::new_v4().simple().to_string()[..16]);
    let installation=Uuid::new_v4();
    let req=json!({"username":username,"password":"batch3-strong-password","display_name":"Batch Three",
        "installation_id":installation,"device_platform":"android"});
    let mut raw=request("POST","/auth/register","",req);
    let octets: [u8;3]=std::array::from_fn(|_|rand::random());
    raw.extensions_mut().insert(ConnectInfo(SocketAddr::from(([10,octets[0],octets[1],octets[2]],12345))));
    let response=app.clone().oneshot(raw).await.unwrap();assert_eq!(response.status(),StatusCode::OK);
    let body:Value=serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
    let token=body["access_token"].as_str().unwrap().to_owned();let claims=jwt::decode_access_token(&state.config,&token).unwrap();
    User {token,refresh:body["refresh_token"].as_str().unwrap().into(),username,installation,
        auth:AuthUser {user_id:claims.sub,device_id:claims.device_id,session_id:claims.session_id}}
}
async fn login(state:&AppState,app:&axum::Router,owner:&User,installation:Uuid)->User {
    let (status,body)=send(app,"POST","/auth/login","",json!({"username":owner.username,"password":"batch3-strong-password",
        "installation_id":installation,"device_platform":"android"})).await;
    assert_eq!(status,StatusCode::OK,"{body}");
    let token=body["access_token"].as_str().unwrap().to_owned();let claims=jwt::decode_access_token(&state.config,&token).unwrap();
    User {token,refresh:body["refresh_token"].as_str().unwrap().into(),username:owner.username.clone(),installation,
        auth:AuthUser {user_id:claims.sub,device_id:claims.device_id,session_id:claims.session_id}}
}
async fn chat(app:&axum::Router,a:&User,b:&User)->Uuid {
    let (s,v)=send(app,"POST","/chats/direct",&a.token,json!({"other_user_id":b.auth.user_id})).await;
    assert_eq!(s,StatusCode::OK,"{v}"); v["chat_id"].as_str().unwrap().parse().unwrap()
}
async fn call(app:&axum::Router,a:&User,b:&User,id:Option<Uuid>)->Value {
    let (s,v)=send(app,"POST","/calls",&a.token,json!({"callee_user_id":b.auth.user_id,"media_kind":"audio","request_id":id})).await;
    assert_eq!(s,StatusCode::OK,"{v}");v
}
async fn family(state:&AppState,user:&User)->Uuid {
    sqlx::query_scalar("SELECT family_id FROM sessions WHERE id=$1").bind(user.auth.session_id).fetch_one(&state.db).await.unwrap()
}

#[tokio::test]
async fn create_retry_returns_persisted_result_after_end_and_conflicting_parameters_fail() {
    let (_state,app)=fixture().await;let a=user(&_state,&app).await;let b=user(&_state,&app).await;chat(&app,&a,&b).await;
    let request_id=Uuid::new_v4();let original=call(&app,&a,&b,Some(request_id)).await;
    let retry=call(&app,&a,&b,Some(request_id)).await;assert_eq!(retry,original);
    let path=format!("/calls/{}/cancel",original["id"].as_str().unwrap());
    assert_eq!(send(&app,"POST",&path,&a.token,Value::Null).await.0,StatusCode::OK);
    assert_eq!(call(&app,&a,&b,Some(request_id)).await,original);
    let (s,_)=send(&app,"POST","/calls",&a.token,json!({"callee_user_id":b.auth.user_id,"media_kind":"video","request_id":request_id})).await;
    assert_eq!(s,StatusCode::CONFLICT);
    let c=call(&app,&a,&b,None).await;
    assert_ne!(c["id"],original["id"]);
    assert_eq!(send(&app,"POST","/calls",&a.token,json!({"callee_user_id":b.auth.user_id,"media_kind":"audio"})).await.0,StatusCode::CONFLICT);
}

#[tokio::test]
async fn caller_signaling_and_lifecycle_are_device_and_family_scoped() {
    use messenger_server::calls::signaling;
    let (state,app)=fixture().await;let a=user(&state,&app).await;let b=user(&state,&app).await;chat(&app,&a,&b).await;
    let other=login(&state,&app,&a,Uuid::new_v4()).await;
    let independent=login(&state,&app,&a,a.installation).await;
    let (_,mut admitted,_ca)=state.ws_registry.register_session(a.auth.device_id,Some(family(&state,&a).await)).await;
    let (_,mut sibling,_cb)=state.ws_registry.register_session(a.auth.device_id,Some(family(&state,&independent).await)).await;
    let c=call(&app,&a,&b,None).await;let id=c["id"].as_str().unwrap().parse().unwrap();
    for outsider in [&other,&independent] {
        assert!(signaling::relay_offer(&state,&outsider.auth,id,"offer".into()).await.is_err());
        assert!(signaling::relay_keep_alive(&state,&outsider.auth,id).await.is_err());
        assert_eq!(send(&app,"POST",&format!("/calls/{id}/failed"),&outsider.token,Value::Null).await.0,StatusCode::NOT_FOUND);
    }
    assert_eq!(send(&app,"POST",&format!("/calls/{id}/accept"),&b.token,Value::Null).await.0,StatusCode::OK);
    let _=admitted.recv().await.unwrap(); // call.accepted
    signaling::relay_answer(&state,&b.auth,id,"answer".into()).await.unwrap();
    assert!(admitted.recv().await.unwrap().contains("call.answer"));assert!(sibling.try_recv().is_err());
    let (status,rotated)=send(&app,"POST","/auth/refresh","",json!({"refresh_token":a.refresh})).await;
    assert_eq!(status,StatusCode::OK,"{rotated}");
    assert!(signaling::relay_keep_alive(&state,&a.auth,id).await.is_ok(),"original socket follows refresh family");
    sqlx::query("UPDATE devices SET revoked_at=clock_timestamp() WHERE id=$1").bind(a.auth.device_id).execute(&state.db).await.unwrap();
    let terminal:(String,Option<String>)=sqlx::query_as("SELECT state,end_reason FROM calls WHERE id=$1").bind(id).fetch_one(&state.db).await.unwrap();
    assert_eq!(terminal,("ended".into(),Some("device_revoked".into())));
    let slots:i64=sqlx::query_scalar("SELECT count(*) FROM call_participants_active WHERE call_id=$1").bind(id).fetch_one(&state.db).await.unwrap();assert_eq!(slots,0);
    assert!(signaling::relay_offer(&state,&a.auth,id,"late".into()).await.is_err());
}

#[tokio::test]
async fn revoking_accepted_callee_closes_the_call_atomically() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;let b=user(&state,&app).await;chat(&app,&a,&b).await;
    let c=call(&app,&a,&b,None).await;let id:Uuid=c["id"].as_str().unwrap().parse().unwrap();
    assert_eq!(send(&app,"POST",&format!("/calls/{id}/accept"),&b.token,Value::Null).await.0,StatusCode::OK);
    let mut tx=state.db.begin().await.unwrap();
    sqlx::query("UPDATE devices SET revoked_at=now() WHERE id=$1").bind(b.auth.device_id).execute(&mut *tx).await.unwrap();
    let ended:bool=sqlx::query_scalar("SELECT state='ended' AND NOT EXISTS(SELECT 1 FROM call_participants_active WHERE call_id=$1) FROM calls WHERE id=$1").bind(id).fetch_one(&mut *tx).await.unwrap();assert!(ended);
    tx.rollback().await.unwrap();
    let active:String=sqlx::query_scalar("SELECT state FROM calls WHERE id=$1").bind(id).fetch_one(&state.db).await.unwrap();assert_eq!(active,"accepted");
}

#[tokio::test]
async fn ringing_sibling_cannot_end_or_advance_a_call_accepted_during_its_lock_wait() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;let b=user(&state,&app).await;
    let winner=login(&state,&app,&b,Uuid::new_v4()).await;chat(&app,&a,&b).await;
    for action in ["failed","connected"] {
        let c=call(&app,&a,&b,None).await;let id:Uuid=c["id"].as_str().unwrap().parse().unwrap();
        let mut held=state.db.begin().await.unwrap();sqlx::query("SELECT id FROM calls WHERE id=$1 FOR UPDATE").bind(id).execute(&mut *held).await.unwrap();
        let router=app.clone();let token=b.token.clone();
        let stale=tokio::spawn(async move {send(&router,"POST",&format!("/calls/{id}/{action}"),&token,Value::Null).await});
        tokio::time::sleep(std::time::Duration::from_millis(150)).await;assert!(!stale.is_finished());
        sqlx::query("UPDATE calls SET state='accepted',accepted_at=now(),accepted_device_id=$2,accepted_session_family=$3 WHERE id=$1")
            .bind(id).bind(winner.auth.device_id).bind(family(&state,&winner).await).execute(&mut *held).await.unwrap();
        held.commit().await.unwrap();assert_eq!(stale.await.unwrap().0,StatusCode::OK);
        let row:(String,Option<Uuid>)=sqlx::query_as("SELECT state,accepted_device_id FROM calls WHERE id=$1").bind(id).fetch_one(&state.db).await.unwrap();
        assert_eq!(row,("accepted".into(),Some(winner.auth.device_id)));
        assert_eq!(send(&app,"POST",&format!("/calls/{id}/hangup"),&a.token,Value::Null).await.0,StatusCode::OK);
    }
}

#[tokio::test]
async fn accept_waiting_on_unchanged_call_row_cannot_cross_ringing_deadline() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;let b=user(&state,&app).await;chat(&app,&a,&b).await;
    let c=call(&app,&a,&b,None).await;let id:Uuid=c["id"].as_str().unwrap().parse().unwrap();
    sqlx::query("UPDATE calls SET ringing_deadline=clock_timestamp()+interval '100 milliseconds' WHERE id=$1").bind(id).execute(&state.db).await.unwrap();
    let mut held=state.db.begin().await.unwrap();sqlx::query("SELECT id FROM calls WHERE id=$1 FOR UPDATE").bind(id).execute(&mut *held).await.unwrap();
    let router=app.clone();let token=b.token.clone();
    let waiter=tokio::spawn(async move {send(&router,"POST",&format!("/calls/{id}/accept"),&token,Value::Null).await});
    tokio::time::sleep(std::time::Duration::from_millis(300)).await;held.commit().await.unwrap();
    let (s,v)=waiter.await.unwrap();assert_eq!(s,StatusCode::CONFLICT,"{v}");
    let (s,v)=send(&app,"GET",&format!("/calls/{id}"),&b.token,Value::Null).await;assert_eq!(s,StatusCode::OK);assert_eq!(v["state"],"ended");assert_eq!(v["end_reason"],"missed");
    let accepted:bool=sqlx::query_scalar("SELECT accepted_at IS NOT NULL FROM calls WHERE id=$1").bind(id).fetch_one(&state.db).await.unwrap();assert!(!accepted);
}

#[tokio::test]
async fn read_only_participants_can_only_signal_and_teardown_existing_calls_idempotently() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;let b=user(&state,&app).await;chat(&app,&a,&b).await;
    for action in ["cancel","reject","hangup","failed"] {
        let c=call(&app,&a,&b,None).await;let id:Uuid=c["id"].as_str().unwrap().parse().unwrap();
        let actor=if action=="reject" {&b}else{&a};
        sqlx::query("INSERT INTO admin_user_controls(user_id,read_only_reason) VALUES($1,'batch3') ON CONFLICT(user_id) DO UPDATE SET read_only_reason='batch3'").bind(actor.auth.user_id).execute(&state.db).await.unwrap();
        messenger_server::calls::signaling::relay_keep_alive(&state,&actor.auth,id).await.unwrap();
        for _ in 0..2 {assert_eq!(send(&app,"POST",&format!("/calls/{id}/{action}"),&actor.token,Value::Null).await.0,StatusCode::OK);}
        assert_eq!(send(&app,"POST","/calls",&actor.token,json!({"callee_user_id":b.auth.user_id,"media_kind":"audio"})).await.0,StatusCode::FORBIDDEN);
        sqlx::query("DELETE FROM admin_user_controls WHERE user_id=$1").bind(actor.auth.user_id).execute(&state.db).await.unwrap();
    }
}

#[tokio::test]
async fn reactivation_records_explicit_event_and_refresh_stays_silent() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;
    sqlx::query("UPDATE devices SET revoked_at=now() WHERE id=$1").bind(a.auth.device_id).execute(&state.db).await.unwrap();
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE device_id=$1").bind(a.auth.device_id).execute(&state.db).await.unwrap();
    let again=login(&state,&app,&a,a.installation).await;assert_eq!(again.auth.device_id,a.auth.device_id);
    let count:i64=sqlx::query_scalar("SELECT count(*) FROM security_events WHERE user_id=$1 AND metadata->>'reactivated'='true'").bind(a.auth.user_id).fetch_one(&state.db).await.unwrap();assert_eq!(count,1);
    assert_eq!(send(&app,"POST","/auth/refresh","",json!({"refresh_token":again.refresh})).await.0,StatusCode::OK);
    let count_after:i64=sqlx::query_scalar("SELECT count(*) FROM security_events WHERE user_id=$1 AND metadata->>'reactivated'='true'").bind(a.auth.user_id).fetch_one(&state.db).await.unwrap();assert_eq!(count_after,1);
}

#[tokio::test]
async fn committed_message_retry_ignores_new_own_device_coverage_but_checks_current_authorization() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;
    let (s,saved)=send(&app,"GET","/chats/saved",&a.token,json!({})).await;assert_eq!(s,StatusCode::OK,"{saved}");
    let id=saved["chat_id"].as_str().unwrap();let request=json!({"client_message_id":Uuid::new_v4(),"envelopes":[],"kind":"content"});
    let (s,ack)=send(&app,"POST",&format!("/chats/{id}/messages"),&a.token,request.clone()).await;assert_eq!(s,StatusCode::OK,"{ack}");
    let other=login(&state,&app,&a,Uuid::new_v4()).await;
    sqlx::query("INSERT INTO device_identity_keys(device_id,identity_key,registration_id) VALUES($1,$2,1)").bind(other.auth.device_id).bind(vec![1u8;32]).execute(&state.db).await.unwrap();
    let (s,retry)=send(&app,"POST",&format!("/chats/{id}/messages"),&a.token,request.clone()).await;assert_eq!(s,StatusCode::OK);assert_eq!(retry,ack);
    let (s,_)=send(&app,"POST",&format!("/chats/{id}/messages"),&a.token,json!({"client_message_id":Uuid::new_v4(),"envelopes":[],"kind":"content"})).await;assert_eq!(s,StatusCode::BAD_REQUEST);
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE id=$1").bind(a.auth.session_id).execute(&state.db).await.unwrap();
    assert_eq!(send(&app,"POST",&format!("/chats/{id}/messages"),&a.token,request).await.0,StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn last_owner_self_delete_is_refused_and_automatic_deletion_skips_it() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;let ordinary=user(&state,&app).await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')").bind(a.auth.user_id).execute(&state.db).await.unwrap();
    let (s,_)=send(&app,"POST","/users/me/delete",&a.token,json!({"password":"batch3-strong-password"})).await;assert_eq!(s,StatusCode::CONFLICT);
    sqlx::query("UPDATE users SET auto_delete_inactive_days=1,auto_delete_last_active_at=clock_timestamp()-interval '3 days',last_seen_at=clock_timestamp()-interval '3 days' WHERE id=ANY($1)").bind(vec![a.auth.user_id,ordinary.auth.user_id]).execute(&state.db).await.unwrap();
    assert_eq!(messenger_server::users::auto_delete::sweep_inactive_accounts(&state).await.unwrap(),1);
    let exists:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)").bind(a.auth.user_id).fetch_one(&state.db).await.unwrap();assert!(exists);
    // Cleanup the fixture's owner without violating the application boundary.
    let b=user(&state,&app).await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')").bind(b.auth.user_id).execute(&state.db).await.unwrap();
    let (s,_)=send(&app,"POST","/users/me/delete",&a.token,json!({"password":"batch3-strong-password"})).await;assert_eq!(s,StatusCode::NO_CONTENT);
    sqlx::query("DELETE FROM server_administrators WHERE user_id=$1").bind(b.auth.user_id).execute(&state.db).await.unwrap();
}

#[tokio::test]
async fn installation_proof_cannot_rebind_or_bypass_restriction_and_fresh_reinstall_is_allowed() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;
    let (s,proof)=send(&app,"POST","/auth/installations","",Value::Null).await;assert_eq!(s,StatusCode::OK);
    let proof=proof["installation_credential"].as_str().unwrap();
    assert_eq!(send(&app,"POST","/devices/me/installation",&a.token,json!({"installation_credential":proof})).await.0,StatusCode::OK);
    let alternate=Uuid::new_v4();
    let login_body=|installation,credential:&str|json!({"username":a.username,"password":"batch3-strong-password",
        "installation_id":installation,"installation_credential":credential,"device_platform":"android"});
    assert_eq!(send(&app,"POST","/auth/login","",login_body(alternate,proof)).await.0,StatusCode::BAD_REQUEST);
    let (_,fresh)=send(&app,"POST","/auth/installations","",Value::Null).await;
    let fresh=fresh["installation_credential"].as_str().unwrap();
    assert_eq!(send(&app,"POST","/devices/me/installation",&a.token,json!({"installation_credential":fresh})).await.0,StatusCode::BAD_REQUEST);
    // Omitting proof on a legacy login must not erase the durable binding.
    let _legacy=login(&state,&app,&a,a.installation).await;
    let still_bound:bool=sqlx::query_scalar("SELECT verified_installation_id IS NOT NULL FROM devices WHERE id=$1").bind(a.auth.device_id).fetch_one(&state.db).await.unwrap();assert!(still_bound);
    sqlx::query("INSERT INTO installation_suspensions(installation_id,source_user_id,reason,actor) VALUES($1,$2,'test',$2)").bind(a.installation).bind(a.auth.user_id).execute(&state.db).await.unwrap();
    assert_eq!(send(&app,"POST","/auth/login","",login_body(alternate,proof)).await.0,StatusCode::FORBIDDEN);
    assert_eq!(send(&app,"POST","/auth/login","",login_body(alternate,fresh)).await.0,StatusCode::OK,
        "a genuine reinstall has a new UUID and new proof; installations are not hardware attestation");
}

#[tokio::test]
async fn historical_verified_alias_restriction_blocks_live_http_refresh_and_conference_admission() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;let b=user(&state,&app).await;
    let vi:Uuid=sqlx::query_scalar("INSERT INTO verified_installations(credential_hash,client_installation_id) VALUES($1,$2) RETURNING id")
        .bind(vec![5u8;32]).bind(a.installation).fetch_one(&state.db).await.unwrap();
    for u in [&a,&b] {
        sqlx::query("UPDATE devices SET verified_installation_id=$2 WHERE id=$1").bind(u.auth.device_id).bind(vi).execute(&state.db).await.unwrap();
        sqlx::query("INSERT INTO verified_installation_aliases VALUES($1,$2)").bind(vi).bind(u.installation).execute(&state.db).await.unwrap();
    }
    sqlx::query("INSERT INTO installation_suspensions(installation_id,source_user_id,reason,actor) VALUES($1,$2,'test',$2)").bind(a.installation).bind(a.auth.user_id).execute(&state.db).await.unwrap();
    assert_eq!(send(&app,"GET","/users/me",&b.token,Value::Null).await.0,StatusCode::UNAUTHORIZED);
    assert_eq!(send(&app,"POST","/auth/refresh","",json!({"refresh_token":b.refresh})).await.0,StatusCode::UNAUTHORIZED);
    let allowed:bool=sqlx::query_scalar("SELECT conference_session_active($1,$2,$3)").bind(b.auth.session_id).bind(b.auth.user_id).bind(b.auth.device_id).fetch_one(&state.db).await.unwrap();assert!(!allowed);
}

#[tokio::test]
async fn bundle_reader_waits_for_publication_and_claims_only_the_new_generation() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;let b=user(&state,&app).await;
    let upload=json!({"identity_key":"b2xk","registration_id":1,
        "signed_prekey":{"key_id":1,"public_key":"b2xk","signature":"b2xk"},
        "kyber_prekey":{"key_id":1,"public_key":"b2xk","signature":"b2xk"},
        "one_time_prekeys":[{"key_id":1,"public_key":"b2xk"}]});
    assert_eq!(send(&app,"POST","/keys",&b.token,upload).await.0,StatusCode::OK);
    let mut publication=state.db.begin().await.unwrap();
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended('key-upload:' || $1::uuid::text,0))").bind(b.auth.device_id).execute(&mut *publication).await.unwrap();
    sqlx::query("UPDATE device_identity_keys SET identity_key=$2,registration_id=2 WHERE device_id=$1").bind(b.auth.device_id).bind(b"new".as_slice()).execute(&mut *publication).await.unwrap();
    let router=app.clone();let token=a.token.clone();let target=b.auth.user_id;
    let reader=tokio::spawn(async move {send(&router,"GET",&format!("/keys/bundles/{target}"),&token,Value::Null).await});
    tokio::time::sleep(std::time::Duration::from_millis(150)).await;
    assert!(!reader.is_finished(),"reader must wait behind the in-progress publication");
    let unused:bool=sqlx::query_scalar("SELECT used_at IS NULL FROM one_time_prekeys WHERE device_id=$1 AND key_id=1").bind(b.auth.device_id).fetch_one(&mut *publication).await.unwrap();assert!(unused);
    for table in ["signed_prekeys","kyber_prekeys"] {
        sqlx::query(&format!("UPDATE {table} SET key_id=2,public_key=$2,signature=$2 WHERE device_id=$1")).bind(b.auth.device_id).bind(b"new".as_slice()).execute(&mut *publication).await.unwrap();
    }
    sqlx::query("DELETE FROM one_time_prekeys WHERE device_id=$1").bind(b.auth.device_id).execute(&mut *publication).await.unwrap();
    sqlx::query("INSERT INTO one_time_prekeys(device_id,key_id,public_key) VALUES($1,2,$2)").bind(b.auth.device_id).bind(b"new".as_slice()).execute(&mut *publication).await.unwrap();
    publication.commit().await.unwrap();
    let (s,v)=reader.await.unwrap();assert_eq!(s,StatusCode::OK);let bundle=&v["devices"][0];
    assert_eq!(bundle["registration_id"],2);assert_eq!(bundle["identity_key"],"bmV3");
    assert_eq!(bundle["signed_prekey_id"],2);assert_eq!(bundle["kyber_prekey_id"],2);assert_eq!(bundle["one_time_prekey_id"],2);
}

struct OfflinePush;
#[async_trait::async_trait]
impl messenger_server::push::PushProvider for OfflinePush {
    async fn send(&self,_token:&str,_event:&messenger_server::push::PushEvent)->Result<messenger_server::push::PushSendOutcome,messenger_server::push::PushError> {
        panic!("the outbox worker must not run in this transaction test")
    }
}

#[tokio::test]
async fn incoming_push_failure_rolls_back_call_and_retry_commits_one_outbox_job() {
    let (mut state,_)=fixture().await;state.push=messenger_server::push::build_with_provider(Arc::new(OfflinePush));
    let app=messenger_server::app(state.clone());let a=user(&state,&app).await;let b=user(&state,&app).await;chat(&app,&a,&b).await;
    assert_eq!(send(&app,"PUT","/devices/me/push-token",&b.token,json!({"provider":"fcm","token":Uuid::new_v4().to_string()})).await.0,StatusCode::OK);
    let fail=format!("CREATE FUNCTION batch3_fail_incoming() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.device_id='{}'::uuid AND NEW.event_type='incoming_call' THEN RAISE EXCEPTION 'batch3 injected insert failure'; END IF; RETURN NEW; END; $$",b.auth.device_id);
    sqlx::query(&fail).execute(&state.db).await.unwrap();
    sqlx::query("CREATE TRIGGER batch3_fail_incoming BEFORE INSERT ON push_outbox FOR EACH ROW EXECUTE FUNCTION batch3_fail_incoming()").execute(&state.db).await.unwrap();
    let request_id=Uuid::new_v4();let body=json!({"callee_user_id":b.auth.user_id,"media_kind":"audio","request_id":request_id});
    assert_eq!(send(&app,"POST","/calls",&a.token,body.clone()).await.0,StatusCode::INTERNAL_SERVER_ERROR);
    let count:i64=sqlx::query_scalar("SELECT count(*) FROM calls WHERE caller_user_id=$1").bind(a.auth.user_id).fetch_one(&state.db).await.unwrap();assert_eq!(count,0);
    let slots:i64=sqlx::query_scalar("SELECT count(*) FROM call_participants_active WHERE user_id=$1").bind(a.auth.user_id).fetch_one(&state.db).await.unwrap();assert_eq!(slots,0);
    sqlx::query("DROP TRIGGER batch3_fail_incoming ON push_outbox").execute(&state.db).await.unwrap();
    sqlx::query("DROP FUNCTION batch3_fail_incoming()").execute(&state.db).await.unwrap();
    let (s,original)=send(&app,"POST","/calls",&a.token,body.clone()).await;assert_eq!(s,StatusCode::OK);
    assert_eq!(send(&app,"POST","/calls",&a.token,body).await.1,original);
    let count:i64=sqlx::query_scalar("SELECT count(*) FROM push_outbox WHERE device_id=$1 AND event_type='incoming_call'").bind(b.auth.device_id).fetch_one(&state.db).await.unwrap();assert_eq!(count,1);
}

#[tokio::test]
async fn revoked_socket_receives_explicit_reason_then_close() {
    use futures_util::{SinkExt,StreamExt};
    use tokio_tungstenite::tungstenite::{client::IntoClientRequest,Message};
    let (state,app)=fixture().await;let a=user(&state,&app).await;let other=login(&state,&app,&a,Uuid::new_v4()).await;
    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let addr=listener.local_addr().unwrap();let router=app.clone();
    let server=tokio::spawn(async move {axum::serve(listener,router.into_make_service_with_connect_info::<SocketAddr>()).await.unwrap();});
    let mut req=format!("ws://{addr}/ws").into_client_request().unwrap();
    req.headers_mut().insert("authorization",format!("Bearer {}",a.token).parse().unwrap());
    let (mut socket,_)=tokio_tungstenite::connect_async(req).await.unwrap();
    socket.send(Message::Text(json!({"type":"PING"}).to_string())).await.unwrap();
    let ready=tokio::time::timeout(std::time::Duration::from_secs(3),socket.next()).await.unwrap().unwrap().unwrap();assert!(matches!(ready,Message::Text(_)));
    assert_eq!(send(&app,"DELETE",&format!("/devices/{}",a.auth.device_id),&other.token,Value::Null).await.0,StatusCode::NO_CONTENT);
    let frame=tokio::time::timeout(std::time::Duration::from_secs(3),socket.next()).await.unwrap().unwrap().unwrap();
    let Message::Text(text)=frame else {panic!("reason must arrive before close")};
    let event:Value=serde_json::from_str(&text).unwrap();assert_eq!(event,json!({"type":"session.revoked","reason":"device_revoked"}));
    let close=tokio::time::timeout(std::time::Duration::from_secs(3),socket.next()).await.unwrap();
    assert!(matches!(close,Some(Ok(Message::Close(_)))|None));server.abort();
}

#[tokio::test]
async fn deletion_waits_for_role_management_and_preserves_new_last_owner() {
    let (state,app)=fixture().await;let a=user(&state,&app).await;let b=user(&state,&app).await;
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')").bind(a.auth.user_id).execute(&state.db).await.unwrap();
    let mut roles=state.db.begin().await.unwrap();
    sqlx::query("SELECT pg_advisory_xact_lock(2392001)").execute(&mut *roles).await.unwrap();
    let router=app.clone();let token=a.token.clone();
    let deletion=tokio::spawn(async move {send(&router,"POST","/users/me/delete",&token,json!({"password":"batch3-strong-password"})).await});
    tokio::time::sleep(std::time::Duration::from_millis(150)).await;assert!(!deletion.is_finished());
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner')").bind(b.auth.user_id).execute(&mut *roles).await.unwrap();
    sqlx::query("UPDATE server_administrators SET role='moderator' WHERE user_id=$1").bind(a.auth.user_id).execute(&mut *roles).await.unwrap();
    roles.commit().await.unwrap();assert_eq!(deletion.await.unwrap().0,StatusCode::NO_CONTENT);
    let owners:i64=sqlx::query_scalar("SELECT count(*) FROM server_administrators WHERE role='owner'").fetch_one(&state.db).await.unwrap();assert_eq!(owners,1);
    sqlx::query("DELETE FROM server_administrators WHERE user_id=$1").bind(b.auth.user_id).execute(&state.db).await.unwrap();
}

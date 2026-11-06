//! Explicitly invoked only against nonce-marked, disposable local databases.
use super::handlers::*;
use crate::{auth::middleware::AuthUser,config::{Config,S3Config,TurnConfig,FcmConfig},state::AppState};
use axum::{body::Body,extract::ConnectInfo,http::{Request,StatusCode}};
use base64::{engine::general_purpose::STANDARD,Engine as _};
use http_body_util::BodyExt;
use serde_json::{json,Value};
use std::sync::Arc;
use tower::ServiceExt;
use uuid::Uuid;

#[tokio::test]
#[ignore = "Requires scripts/qa/Test-KeysDiscovery.ps1 disposable services"]
async fn isolated_discovery_does_not_consume_prekeys() {
    let url=std::env::var("COCO_KEYS_REDIS_QA_URL").expect("isolated Redis");
    let database=std::env::var("COCO_KEYS_QA_DATABASE_URL").expect("isolated database");
    assert!(url.starts_with("redis://127.0.0.1:"));
    assert!(database.starts_with("postgres://performance_qa:fixture@127.0.0.1:"));
    let redis=redis::Client::open(url.clone()).unwrap().get_connection_manager().await.unwrap();
    let mut probe=redis.clone();
    let nonce:String=redis::cmd("GET").arg("coco:keys:qa:nonce").query_async(&mut probe).await.unwrap();
    assert_eq!(nonce,std::env::var("COCO_KEYS_QA_NONCE").unwrap());
    let db=sqlx::postgres::PgPoolOptions::new().max_connections(3).connect(&database).await.unwrap();
    sqlx::migrate!().run(&db).await.unwrap();
    let s3=S3Config {
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS").ok().and_then(|v|v.parse().ok()).unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(), upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),
endpoint_url:"http://127.0.0.1:1".into(),public_endpoint_url:"http://127.0.0.1:1".into(),region:"fixture".into(),bucket:"fixture".into(),access_key_id:"fixture".into(),secret_access_key:"fixture".into(),presign_ttl_seconds:60};
    let config=Config {host:"127.0.0.1".into(),port:1,database_url:database,database_max_connections:3,redis_url:url,jwt_secret:"fixture".into(),trusted_proxies:vec![],jwt_issuer:"fixture".into(),jwt_audience:"fixture".into(),access_token_ttl_seconds:600,refresh_token_ttl_seconds:600,s3:s3.clone(),turn:TurnConfig {stun_urls:vec![],turn_urls:vec![],shared_secret:"fixture".into(),credential_ttl_seconds:60},fcm:FcmConfig::default()};
    let state=AppState { resources: Default::default(),db,redis,config,ws_registry:Default::default(),s3:Arc::new(crate::storage::S3Client::new(s3)),push:crate::push::disabled()};
    let app=crate::app(state.clone());
    let request=|method:&str,path:&str,token:Option<&str>,body:Value| {
        let mut b=Request::builder().method(method).uri(path).header("content-type","application/json")
            .extension(ConnectInfo(std::net::SocketAddr::from(([127,0,0,2],12345))));
        if let Some(token)=token { b=b.header("authorization",format!("Bearer {token}")); }
        b.body(Body::from(body.to_string())).unwrap()
    };
    let register=json!({"username":"keys_fixture","password":"fixture-only-password-123","display_name":"Fixture","installation_id":Uuid::new_v4(),"device_platform":"ios","device_name":"Fixture"});
    let response=app.clone().oneshot(request("POST","/auth/register",None,register)).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);
    let login:Value=serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
    let token=login["access_token"].as_str().unwrap();
    let claims=crate::auth::jwt::decode_access_token(&state.config,token).unwrap();
    let user=claims.sub;
    let device=claims.device_id;
    let key=STANDARD.encode(b"fixture-key");
    let upload=json!({"identity_key":key,"registration_id":12345,"signed_prekey":{"key_id":1,"public_key":key,"signature":key},"kyber_prekey":{"key_id":1,"public_key":key,"signature":key},"one_time_prekeys":[{"key_id":1,"public_key":key},{"key_id":2,"public_key":key}]});
    let response=app.clone().oneshot(request("POST","/keys",Some(token),upload)).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);
    let path=format!("/keys/devices/{user}");
    assert_eq!(app.clone().oneshot(request("GET",&path,None,Value::Null)).await.unwrap().status(),StatusCode::UNAUTHORIZED);
    for _ in 0..50 {
        let response=app.clone().oneshot(request("GET",&path,Some(token),Value::Null)).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);
        assert_eq!(response.headers()["cache-control"],"no-store");
        let data:Value=serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
        assert_eq!(data["devices"].as_array().unwrap().len(),1);
        assert!(data["devices"][0]["one_time_prekey_id"].is_null());
    }
    let unused:i64=sqlx::query_scalar("SELECT count(*) FROM one_time_prekeys WHERE device_id=$1 AND used_at IS NULL").bind(device).fetch_one(&state.db).await.unwrap();
    assert_eq!(unused,2,"Discovery must not spend OTPs");
    let bundle_path=format!("/keys/bundles/{user}");
    let response=app.clone().oneshot(request("GET",&bundle_path,Some(token),Value::Null)).await.unwrap();
    assert_eq!(response.status(),StatusCode::OK);
    let unused:i64=sqlx::query_scalar("SELECT count(*) FROM one_time_prekeys WHERE device_id=$1 AND used_at IS NULL").bind(device).fetch_one(&state.db).await.unwrap();
    assert_eq!(unused,1,"Establishment retains normal OTP consumption");
    let auth=AuthUser {user_id:user,device_id:device,session_id:Uuid::new_v4()};
    let ip=crate::net::ClientIp(std::net::Ipv4Addr::from([127,0,0,2]).into());
    for _ in 0..9 { let _ = get_bundles(axum::extract::State(state.clone()),ip,auth.clone(),axum::extract::Path(user)).await.unwrap(); }
    assert!(matches!(get_bundles(axum::extract::State(state.clone()),ip,auth.clone(),axum::extract::Path(user)).await,Err(crate::error::AppError::RateLimited)));
    // Discovery retains its independent bounded budget when establishment is limited.
    let _ = get_device_bundles(axum::extract::State(state.clone()),ip,auth.clone(),axum::extract::Path(user)).await.unwrap();
    sqlx::query("UPDATE device_identity_keys SET identity_key=$1 WHERE device_id=$2").bind(b"changed-fixture".to_vec()).bind(device).execute(&state.db).await.unwrap();
    let (_,axum::Json(changed))=get_device_bundles(axum::extract::State(state.clone()),ip,auth.clone(),axum::extract::Path(user)).await.unwrap();
    assert_eq!(changed.devices[0].identity_key,STANDARD.encode(b"changed-fixture"));
    sqlx::query("UPDATE devices SET revoked_at=now() WHERE id=$1").bind(device).execute(&state.db).await.unwrap();
    let (_,axum::Json(revoked))=get_device_bundles(axum::extract::State(state.clone()),ip,auth,axum::extract::Path(user)).await.unwrap();
    assert!(revoked.devices.is_empty());
    // The actual revoke endpoint revokes the device AND all its sessions.
    sqlx::query("UPDATE sessions SET revoked_at=now() WHERE device_id=$1").bind(device).execute(&state.db).await.unwrap();
    let status=app.oneshot(request("GET",&path,Some(token),Value::Null)).await.unwrap().status();
    assert_eq!(status,StatusCode::UNAUTHORIZED,"Revoked session must not authorize discovery");
}

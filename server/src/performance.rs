//! Opt-in performance metadata only. No paths, message IDs or free-text events.
use axum::{extract::{Query, State}, http::StatusCode, Json};
use chrono::{Duration, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use uuid::Uuid;
use crate::{admin::Admin, auth::{middleware::AuthUser, rate_limit}, error::AppError, net::ClientIp, state::AppState};

pub const MAX_SNAPSHOT_BYTES: usize = 256 * 1024;
const METRICS: &[&str] = &["duration_ms","gap_ms","budget_ms","frames","late_frames","max_gap_ms","memory_mb","disk_free_mb","thermal","low_power","network","count","bytes","cache_hit","status","success","cancelled","queue_count","visible_count","items","fps","maximum_fps","phase","direction","width","height","span","operation","trace"];
const KINDS: &[&str] = &["app_active","app_inactive","screen_open","screen_close","touch","action","frame_summary","frame_gap","main_stall","keyboard_show","keyboard_hide","memory","memory_warning","network","thermal","low_power","chat_open","history_load","media_page","video_ready","preview_decode","preview_cache","media_prepare","media_compress","media_upload","media_download","message_send","network_request","network_error","queue","mark_problem","call_state","ui_update"];
const SCREENS: &[&str] = &["unknown","app","login","chats","chat","search","settings","media","gallery","call","report"];

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Event {
    sequence: u64,
    at_ms: i64,
    kind: String,
    screen: String,
    metrics: BTreeMap<String, f64>,
}
#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Snapshot {
    schema_version: u8,
    recording_id: Uuid,
    platform: String,
    app_version: String,
    build: String,
    os_version: String,
    hardware: String,
    dropped: u64,
    #[serde(default)]
    omitted: u64,
    events: Vec<Event>,
}
fn version(value: &str) -> bool {
    !value.is_empty() && value.len() <= 64 && value.bytes().all(|b| b.is_ascii_alphanumeric() || b".,_-+".contains(&b))
}
impl Snapshot {
    pub fn validate(&self, maximum_events: usize) -> Result<(), AppError> {
        let bad = || AppError::Validation("Invalid performance diagnostics".into());
        if self.schema_version != 1 || self.recording_id.is_nil() || !["ios","android"].contains(&self.platform.as_str()) ||
            ![&self.app_version,&self.build,&self.os_version,&self.hardware].iter().all(|s|version(s)) ||
            self.events.is_empty() || self.events.len() > maximum_events { return Err(bad()); }
        let mut previous = 0;
        for event in &self.events {
            if event.sequence <= previous || event.at_ms < 0 || !KINDS.contains(&event.kind.as_str()) ||
                !SCREENS.contains(&event.screen.as_str()) || event.metrics.len() > 16 ||
                !event.metrics.iter().all(|(key,value)| METRICS.contains(&key.as_str()) && value.is_finite() && *value >= 0.0 && *value <= 1_000_000_000_000.0) { return Err(bad()); }
            previous = event.sequence;
        }
        if serde_json::to_vec(self).map_err(|_| bad())?.len() > MAX_SNAPSHOT_BYTES { return Err(bad()); }
        Ok(())
    }
}

pub async fn ingest(State(state): State<AppState>, ClientIp(ip): ClientIp, auth: AuthUser, Json(snapshot): Json<Snapshot>) -> Result<StatusCode, AppError> {
    snapshot.validate(64)?;
    if serde_json::to_vec(&snapshot).map_err(|_|AppError::Internal)?.len() > 32 * 1024 {
        return Err(AppError::Validation("Performance batch is too large".into()));
    }
    rate_limit::enforce(&state,"performance:ip",ip,240,60).await?;
    rate_limit::enforce(&state,&format!("performance:device:{}",auth.device_id),std::net::Ipv4Addr::UNSPECIFIED.into(),20,60).await?;
    let now = Utc::now();
    let key = format!("performance:v1:{}",now.format("%Y%m%d%H"));
    let payload=json!({"received_at":now.to_rfc3339(),"user_id":auth.user_id,"device_id":auth.device_id,"snapshot":snapshot}).to_string();
    let mut redis=state.redis.clone();
    // At most 24 hourly buckets, 60 x <=32 KiB batches each. Expiry is tied
    // to the hour, not extended indefinitely by subsequent traffic.
    let expiry=(now.timestamp()/3600)*3600+86400;
    // A request admitted before deletion must not append owned samples after
    // the durable post-commit purge. Hold the same account lock across Redis IO.
    let mut tx=state.db.begin().await?;
    crate::auth::middleware::lock_active_session(&mut tx,&auth).await?;
    let _:()=redis::pipe().atomic().cmd("LPUSH").arg(&key).arg(payload).ignore()
        .cmd("LTRIM").arg(&key).arg(0).arg(59).ignore()
        .cmd("EXPIREAT").arg(&key).arg(expiry).ignore()
        .query_async(&mut redis).await.map_err(|_|AppError::Internal)?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

/// Shared hourly rings are filtered atomically without touching unrelated
/// accounts or extending their TTL. Fixed retention bounds both keys and rows.
pub async fn delete_account_samples(state:&AppState, users:&[Uuid])->Result<(),AppError> {
    let script=redis::Script::new(r#"
        local owners = {}
        for _, id in ipairs(ARGV) do owners[id] = true end
        for _, key in ipairs(KEYS) do
            local rows = redis.call('LRANGE', key, 0, 59)
            for _, row in ipairs(rows) do
                local ok, value = pcall(cjson.decode, row)
                if ok and type(value) == 'table' and owners[value['user_id']] then
                    redis.call('LREM', key, 1, row)
                end
            end
        end
        return 1
    "#);
    let mut invocation=script.prepare_invoke();
    let now=Utc::now();
    for hour in 0..=24 { invocation.key(format!("performance:v1:{}",(now-Duration::hours(hour)).format("%Y%m%d%H"))); }
    for user in users { invocation.arg(user.to_string()); }
    let mut redis=state.redis.clone();
    let _:i64=invocation.invoke_async(&mut redis).await.map_err(|_|AppError::Internal)?;
    Ok(())
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LiveQuery { device_id: Option<Uuid> }

pub async fn live(State(state): State<AppState>, admin: Admin, Query(query): Query<LiveQuery>) -> Result<Json<Value>, AppError> {
    if admin.role != "owner" { return Err(AppError::NotFound("Not found.".into())); }
    let mut redis=state.redis.clone();
    let mut items=Vec::new();
    let now=Utc::now();
    for hour in 0..24 {
        let key=format!("performance:v1:{}",(now-Duration::hours(hour)).format("%Y%m%d%H"));
        let rows:Vec<String>=redis::cmd("LRANGE").arg(&key).arg(0).arg(59).query_async(&mut redis).await.map_err(|_|AppError::Internal)?;
        let mut candidates=Vec::new();
        for row in rows {
            if row.len()>40*1024 {continue;}
            let Ok(value)=serde_json::from_str::<Value>(&row) else {continue};
            if let Some(device)=query.device_id { if value["device_id"].as_str()!=Some(device.to_string().as_str()) {continue;} }
            if let Some(owner)=value["user_id"].as_str().and_then(|id|Uuid::parse_str(id).ok()) {
                candidates.push((owner,value));
            }
        }
        if candidates.is_empty() { continue; }
        // A durable Redis purge can be retrying after a crash. Relational
        // deletion immediately removes admin access to that account's samples.
        let owners:Vec<Uuid>=candidates.iter().map(|(owner,_)|*owner).collect();
        let active:std::collections::HashSet<Uuid>=sqlx::query_scalar::<_,Uuid>("SELECT id FROM users WHERE id=ANY($1) AND deleted_at IS NULL")
            .bind(owners).fetch_all(&state.db).await?.into_iter().collect();
        for (owner,value) in candidates {
            if !active.contains(&owner) { continue; }
            items.push(value);
            if items.len()==20 { return Ok(Json(json!({"items":items,"retention_hours":24}))); }
        }
    }
    Ok(Json(json!({"items":items,"retention_hours":24})))
}

#[cfg(test)] mod tests {
    use super::*;
    fn valid()->Value {json!({"schema_version":1,"recording_id":Uuid::new_v4(),"platform":"ios","app_version":"0.2.0","build":"20260925-test","os_version":"26.0","hardware":"iPhone16,2","dropped":0,"events":[{"sequence":1,"at_ms":1760000000000_i64,"kind":"frame_gap","screen":"chat","metrics":{"gap_ms":48.0,"budget_ms":8.3}}]})}
    #[test] fn accepts_bounded_numeric_metadata() { let s:Snapshot=serde_json::from_value(valid()).unwrap();assert!(s.validate(64).is_ok()); }
    #[test] fn accepts_numeric_trace_but_not_raw_identifier() {
        let mut value=valid();value["events"][0]["metrics"]["trace"]=json!(42);
        assert!(serde_json::from_value::<Snapshot>(value.clone()).unwrap().validate(64).is_ok());
        value["events"][0]["metrics"]["trace"]=json!("private-message-id");
        assert!(serde_json::from_value::<Snapshot>(value).is_err());
    }
    #[test] fn rejects_content_paths_and_unknown_names() {
        let mut v=valid();v["text"]=json!("private");assert!(serde_json::from_value::<Snapshot>(v).is_err());
        let mut v=valid();v["events"][0]["metrics"]["password"]=json!(1);assert!(serde_json::from_value::<Snapshot>(v).unwrap().validate(64).is_err());
        let mut v=valid();v["events"][0]["kind"]=json!("a private message");assert!(serde_json::from_value::<Snapshot>(v).unwrap().validate(64).is_err());
        let mut v=valid();v["hardware"]=json!("Dima's iPhone");assert!(serde_json::from_value::<Snapshot>(v).unwrap().validate(64).is_err());
        let mut v=valid();v["events"][0]["url"]=json!("https://private/path");assert!(serde_json::from_value::<Snapshot>(v).is_err());
    }
    #[test] fn bounds_count_order_and_numbers() {
        let mut v=valid();v["events"][0]["metrics"]["gap_ms"]=json!(-1);assert!(serde_json::from_value::<Snapshot>(v).unwrap().validate(64).is_err());
        let mut v=valid();let e=v["events"][0].clone();v["events"].as_array_mut().unwrap().push(e);assert!(serde_json::from_value::<Snapshot>(v).unwrap().validate(64).is_err());
        let s:Snapshot=serde_json::from_value(valid()).unwrap();assert!(s.validate(0).is_err());
    }
    #[tokio::test]
    #[ignore = "Only run through scripts/qa/Test-PerformanceRedis.ps1 against its owned ephemeral Redis"]
    async fn isolated_redis_roundtrip_limits_identity_role_and_expiry() {
        use crate::config::{Config,S3Config,TurnConfig,FcmConfig};
        use std::sync::Arc;
        let url=std::env::var("COCO_PERFORMANCE_REDIS_QA_URL").expect("isolated QA URL");
        assert!(url.starts_with("redis://127.0.0.1:"));
        let redis=redis::Client::open(url.clone()).unwrap().get_connection_manager().await.unwrap();
        let mut probe=redis.clone();
        let marker:String=redis::cmd("GET").arg("coco:performance:qa:nonce").query_async(&mut probe).await.unwrap();
        assert_eq!(marker,std::env::var("COCO_PERFORMANCE_QA_NONCE").unwrap(),"Refuse a Redis not owned by this test");
        let s3=S3Config {
        max_upload_duration_seconds: std::env::var("S3_MAX_UPLOAD_DURATION_SECONDS").ok().and_then(|v|v.parse().ok()).unwrap_or(0),
        upload_access_key_id: std::env::var("S3_UPLOAD_ACCESS_KEY_ID").unwrap_or_default(), upload_secret_access_key: std::env::var("S3_UPLOAD_SECRET_ACCESS_KEY").unwrap_or_default(),
endpoint_url:"http://127.0.0.1:1".into(),public_endpoint_url:"http://127.0.0.1:1".into(),region:"fixture".into(),bucket:"fixture".into(),access_key_id:"fixture".into(),secret_access_key:"fixture".into(),presign_ttl_seconds:60};
        let config=Config {host:"127.0.0.1".into(),port:1,database_url:"postgres://fixture:fixture@127.0.0.1:1/fixture".into(),database_max_connections:1,redis_url:url,jwt_secret:"fixture".into(),trusted_proxies:vec![],jwt_issuer:"fixture".into(),jwt_audience:"fixture".into(),access_token_ttl_seconds:60,refresh_token_ttl_seconds:60,s3:s3.clone(),turn:TurnConfig {stun_urls:vec![],turn_urls:vec![],shared_secret:"fixture".into(),credential_ttl_seconds:60},fcm:FcmConfig::default()};
        let database=std::env::var("COCO_PERFORMANCE_QA_DATABASE_URL").expect("owned ephemeral PostgreSQL is required for active-session validation");
        assert!(database.starts_with("postgres://performance_qa:fixture@127.0.0.1:"));
        let db=sqlx::postgres::PgPoolOptions::new().max_connections(3).connect(&database).await.unwrap();
        sqlx::migrate!().run(&db).await.unwrap();
        let state=AppState { resources: Default::default(),db,redis,config,ws_registry:Arc::new(crate::websocket::registry::ConnectionRegistry::default()),s3:Arc::new(crate::storage::S3Client::new(s3)),push:crate::push::disabled()};
        let user=Uuid::new_v4();let last_device=Uuid::new_v4();
        sqlx::query("INSERT INTO users(id,username,display_name,password_hash) VALUES($1,$2,'Performance fixture','fixture-only')")
            .bind(user).bind(format!("perf{}",user.simple()).chars().take(32).collect::<String>()).execute(&state.db).await.unwrap();
        let mut last_session=Uuid::nil();
        for index in 0..70 {
            let device=if index==69 {last_device} else {Uuid::new_v4()};
            let session=Uuid::new_v4();
            sqlx::query("INSERT INTO devices(id,user_id,platform,signal_device_id,installation_id) VALUES($1,$2,'ios',$3,$4)")
                .bind(device).bind(user).bind(index+1).bind(Uuid::new_v4()).execute(&state.db).await.unwrap();
            sqlx::query("INSERT INTO sessions(id,user_id,device_id,refresh_token_hash,expires_at) VALUES($1,$2,$3,$4,now()+interval '1 hour')")
                .bind(session).bind(user).bind(device).bind(Uuid::new_v4().to_string()).execute(&state.db).await.unwrap();
            if index==69 {last_session=session;}
            let auth=AuthUser {user_id:user,device_id:device,session_id:session};
            let result=ingest(State(state.clone()),ClientIp(std::net::Ipv4Addr::LOCALHOST.into()),auth,Json(serde_json::from_value(valid()).unwrap())).await.unwrap();
            assert_eq!(result,StatusCode::NO_CONTENT);
        }
        let key=format!("performance:v1:{}",Utc::now().format("%Y%m%d%H"));
        let count:i64=redis::cmd("LLEN").arg(&key).query_async(&mut probe).await.unwrap();assert_eq!(count,60);
        let ttl:i64=redis::cmd("TTL").arg(&key).query_async(&mut probe).await.unwrap();assert!((1..=86400).contains(&ttl));
        let admin=|role:&str| Admin {user,role:role.into(),token_hash:"fixture".into(),auth_session:Uuid::new_v4(),channel:crate::admin::AdminChannel::Native};
        assert!(matches!(live(State(state.clone()),admin("moderator"),Query(LiveQuery::default())).await,Err(AppError::NotFound(_))));
        let Json(response)=live(State(state.clone()),admin("owner"),Query(LiveQuery::default())).await.unwrap();assert_eq!(response["items"].as_array().unwrap().len(),20);
        let Json(filtered)=live(State(state.clone()),admin("owner"),Query(LiveQuery {device_id:Some(last_device)})).await.unwrap();
        assert_eq!(filtered["items"].as_array().unwrap().len(),1);
        assert_eq!(filtered["items"][0]["user_id"],json!(user));assert_eq!(filtered["items"][0]["device_id"],json!(last_device));
        for _ in 0..19 {
            let auth=AuthUser {user_id:user,device_id:last_device,session_id:last_session};
            ingest(State(state.clone()),ClientIp(std::net::Ipv4Addr::LOCALHOST.into()),auth,Json(serde_json::from_value(valid()).unwrap())).await.unwrap();
        }
        let auth=AuthUser {user_id:user,device_id:last_device,session_id:last_session};
        assert!(matches!(ingest(State(state.clone()),ClientIp(std::net::Ipv4Addr::LOCALHOST.into()),auth,Json(serde_json::from_value(valid()).unwrap())).await,Err(AppError::RateLimited)));
        if let Ok(database)=std::env::var("COCO_PERFORMANCE_QA_DATABASE_URL") {
            assert!(database.starts_with("postgres://performance_qa:fixture@127.0.0.1:"));
            let mut http_state=state;
            http_state.db=sqlx::postgres::PgPoolOptions::new().max_connections(3).connect(&database).await.unwrap();
            sqlx::migrate!().run(&http_state.db).await.unwrap();
            http_roundtrip(http_state).await;
        }
    }

    async fn http_roundtrip(state:AppState) {
        use axum::{body::Body,extract::ConnectInfo,http::Request};
        use http_body_util::BodyExt;
        use tower::ServiceExt;
        let app=crate::app(state.clone());
        let request=|method:&str,path:&str,token:Option<&str>,admin:Option<&str>,body:Value| {
            let mut b=Request::builder().method(method).uri(path).header("content-type","application/json")
                .extension(ConnectInfo(std::net::SocketAddr::from(([127,0,0,2],12345))));
            if let Some(t)=token {b=b.header("authorization",format!("Bearer {t}"));}
            if let Some(t)=admin {b=b.header("x-coco-admin",t);}
            b.body(Body::from(body.to_string())).unwrap()
        };
        let register=json!({"username":"performance_fixture","password":"fixture-only-password-123","display_name":"Fixture","installation_id":Uuid::new_v4(),"device_platform":"ios","device_name":"Fixture"});
        let response=app.clone().oneshot(request("POST","/auth/register",None,None,register)).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);
        let identity:Value=serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
        let token=identity["access_token"].as_str().unwrap();
        let claims=crate::auth::jwt::decode_access_token(&state.config,token).unwrap();
        let snapshot=valid();
        let response=app.clone().oneshot(request("POST","/performance",None,None,snapshot.clone())).await.unwrap();
        assert_eq!(response.status(),StatusCode::UNAUTHORIZED);
        let response=app.clone().oneshot(request("POST","/performance",Some(token),None,snapshot.clone())).await.unwrap();
        assert_eq!(response.status(),StatusCode::NO_CONTENT);
        let response=app.clone().oneshot(request("GET","/admin/performance",Some(token),None,Value::Null)).await.unwrap();
        assert_eq!(response.status(),StatusCode::NOT_FOUND);
        let user=Uuid::parse_str(identity["user"]["id"].as_str().unwrap()).unwrap();
        let session:Uuid=sqlx::query_scalar("SELECT id FROM sessions WHERE user_id=$1").bind(user).fetch_one(&state.db).await.unwrap();
        let admin="a".repeat(43);let public=vec![1u8;32];
        sqlx::query("INSERT INTO server_administrators(user_id,role,secret,signing_public_key) VALUES($1,'owner',$2,$3)")
            .bind(user).bind(vec![2u8;32]).bind(&public).execute(&state.db).await.unwrap();
        sqlx::query("INSERT INTO admin_sessions(token_hash,user_id,auth_session,signing_public_key) VALUES($1,$2,$3,$4)")
            .bind(crate::admin::hash(&admin)).bind(user).bind(session).bind(public).execute(&state.db).await.unwrap();
        let response=app.clone().oneshot(request("GET","/admin/performance",Some(token),Some(&admin),Value::Null)).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);assert_eq!(response.headers()["cache-control"],"no-store");
        let value:Value=serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
        assert!(value["items"].as_array().unwrap().iter().any(|v|v["user_id"]==json!(user)));
        let _=claims; // The real middleware and database session were exercised above.
        let id=Uuid::new_v4();
        let report=json!({"id":id,"description":"Synthetic performance report","platform":"ios","app_version":"1","os_version":"26","screenshots":[],"performance":snapshot});
        for _ in 0..2 {
            let response=app.clone().oneshot(request("POST","/feedback",Some(token),None,report.clone())).await.unwrap();
            assert_eq!(response.status(),StatusCode::OK);
        }
        let count:i64=sqlx::query_scalar("SELECT count(*) FROM bug_reports WHERE id=$1").bind(id).fetch_one(&state.db).await.unwrap();assert_eq!(count,1);
        let response=app.clone().oneshot(request("GET",&format!("/admin/feedback/{id}"),Some(token),Some(&admin),Value::Null)).await.unwrap();
        assert_eq!(response.status(),StatusCode::OK);
        let value:Value=serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
        assert_eq!(value["performance"]["events"][0]["kind"],"frame_gap");
        sqlx::query("UPDATE sessions SET revoked_at=now() WHERE id=$1").bind(session).execute(&state.db).await.unwrap();
        let response=app.oneshot(request("POST","/performance",Some(token),None,valid())).await.unwrap();
        assert!(!response.status().is_success());
    }
}

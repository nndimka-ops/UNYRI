use std::sync::Arc;
use std::time::Duration;

use sqlx::PgPool;
use uuid::Uuid;

use crate::push::models::{PushEvent, PushProvider, PushSendOutcome};

/// Maximum retry attempts before an outbox row is terminal (bounded retry -
/// never retry forever, Этап K.3).
pub const MAX_ATTEMPTS: i32 = 8;

/// How long a claimed row is leased (in-flight) before another worker may
/// reclaim it after a crash mid-send.
const LEASE_SECONDS: i64 = 300;

#[derive(sqlx::FromRow)]
struct ApnsRow {
    token: String, environment: Option<String>, topic: Option<String>,
    registration_id: Uuid, revision: i64, token_registered_at: chrono::DateTime<chrono::Utc>,
    message_alert_enabled: bool, message_sound_enabled: bool, permission_allowed: bool,
}

/// Durable push dispatcher. When FCM is disabled (no credentials) it is a
/// no-op that never writes outbox rows - local dev stays clean.
#[derive(Clone)]
pub struct PushService {
    provider: Option<Arc<dyn PushProvider>>,
    apns: Vec<Arc<super::apns::Provider>>,
    lanes: [Arc<tokio::sync::Semaphore>; 3],
}

impl PushService {
    pub fn disabled() -> Self {
        Self { provider: None, apns: Vec::new(), lanes: std::array::from_fn(|_| Arc::new(tokio::sync::Semaphore::new(1))) }
    }

    pub fn new(provider: Arc<dyn PushProvider>) -> Self {
        Self { provider: Some(provider), apns: Vec::new(), lanes: std::array::from_fn(|_| Arc::new(tokio::sync::Semaphore::new(1))) }
    }

    pub fn enabled(&self) -> bool {
        self.provider.is_some() || !self.apns.is_empty()
    }

    pub fn with_apns(mut self, providers: Vec<Arc<super::apns::Provider>>) -> Self {
        self.apns = providers;
        self
    }

    pub fn admits_apns(&self, kind: super::apns::Kind, environment: super::apns::Environment, topic: &str) -> bool {
        self.apns.iter().any(|p| p.environment() == environment && p.allows(kind, topic))
    }

    fn providers_for(&self, event: &str) -> Vec<String> {
        let mut result = Vec::new();
        if self.provider.is_some() { result.push("fcm".to_owned()); }
        if !self.apns.is_empty() {
            if event == "incoming_call" { result.push("apns_voip".to_owned()); }
            if matches!(event, "new_message" | "security_event" | "admin_notice") { result.push("apns".to_owned()); }
        }
        result
    }

    /// Enqueues one outbox row per active device (with a valid, non-invalidated
    /// push token) belonging to any of `user_ids`, excluding `except_device_id`.
    /// Best-effort by design: failures here never fail the call/message
    /// transaction that triggered the push (already committed upstream).
    pub async fn enqueue_for_users(
        &self,
        db: &PgPool,
        user_ids: &[Uuid],
        except_device_id: Option<Uuid>,
        event: &PushEvent,
    ) {
        if !self.enabled() || user_ids.is_empty() {
            return;
        }
        let payload = match serde_json::to_value(event) {
            Ok(value) => value,
            Err(err) => {
                tracing::error!(%err, "failed to serialize push event");
                return;
            }
        };
        let providers = self.providers_for(&event.event);
        let rows: Vec<(Uuid, Uuid, String)> = match sqlx::query_as(
            "SELECT d.id, d.user_id, pt.provider::text FROM devices d \
             JOIN push_tokens pt ON pt.device_id = d.id \
             WHERE d.user_id = ANY($1) AND d.revoked_at IS NULL AND pt.invalidated_at IS NULL \
               AND d.id <> COALESCE($2, '00000000-0000-0000-0000-000000000000') AND pt.provider::text=ANY($3)",
        )
        .bind(user_ids)
        .bind(except_device_id)
        .bind(&providers)
        .fetch_all(db)
        .await
        {
            Ok(rows) => rows,
            Err(err) => {
                tracing::error!(%err, "failed to look up push targets");
                return;
            }
        };
        if rows.is_empty() {
            return;
        }
        // Do not borrow another pool connection while holding an outbox
        // transaction: concurrent sends could otherwise exhaust the pool.
        let mut eligible=Vec::with_capacity(rows.len());
        for (device,user,provider) in rows {
            if crate::users::blocks::allows_push(db,device,event).await {eligible.push((device,user,provider));}
        }
        if eligible.is_empty() {return;}
        let mut tx = match db.begin().await {
            Ok(tx) => tx,
            Err(err) => {
                tracing::error!(%err, "failed to begin push outbox transaction");
                return;
            }
        };
        for (device_id, user_id, provider) in eligible {
            if let Err(err) = sqlx::query(
                "INSERT INTO push_outbox (event_type, user_id, device_id, payload,provider) VALUES ($1, $2, $3, $4,$5::push_provider)",
            )
            .bind(&event.event)
            .bind(user_id)
            .bind(device_id)
            .bind(&payload)
            .bind(provider)
            .execute(&mut *tx)
            .await
            {
                tracing::error!(%err, "failed to enqueue push outbox row");
            }
        }
        if let Err(err) = tx.commit().await {
            tracing::error!(%err, "failed to commit push outbox rows");
        }
    }

    /// Queue an administrative notice in the caller's transaction. A failure rolls
    /// back the notice and audit too, so the UI can safely report the outcome.
    pub async fn enqueue_transactional(
        &self,
        tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        user_id: Uuid,
        event: &PushEvent,
    ) -> Result<u64, crate::error::AppError> {
        if !self.enabled() { return Ok(0); }
        let payload = serde_json::to_value(event)
            .map_err(|_| crate::error::AppError::Internal)?;
        let result = sqlx::query(
            "INSERT INTO push_outbox(event_type,user_id,device_id,payload,provider)
             SELECT $1,d.user_id,d.id,$2,pt.provider FROM devices d
             JOIN push_tokens pt ON pt.device_id=d.id
             WHERE d.user_id=$3 AND d.revoked_at IS NULL AND pt.invalidated_at IS NULL AND pt.provider::text=ANY($4)",
        ).bind(&event.event).bind(payload).bind(user_id).bind(self.providers_for(&event.event)).execute(&mut **tx).await?;
        Ok(result.rows_affected())
    }

    /// One sweep over due outbox rows. Claims with `FOR UPDATE SKIP LOCKED`
    /// (concurrency-safe: two workers never deliver the same row), leases each
    /// row forward immediately so a crash mid-send eventually retries, then
    /// dispatches outside the transaction. Returns how many rows were claimed.
    pub async fn process_due(&self, db: &PgPool) -> usize {
        if !self.enabled() {
            return 0;
        }
        let mut providers = Vec::new();
        if self.provider.is_some() {
            providers.push("fcm".to_owned());
        }
        if !self.apns.is_empty() {
            providers.extend(["apns".to_owned(), "apns_voip".to_owned()]);
        }
        // One existing worker, three bounded lanes. A held ordinary request
        // cannot prevent the free call lanes from being polled. Permits are
        // shared by service clones and drop on cancellation/panic as well.
        let mut tasks = tokio::task::JoinSet::new();
        let mut owners = std::collections::HashMap::new();
        let mut busy = [false; 3];
        let mut count = 0;
        let mut bulk = 0;
        loop {
            for lane in 0..3 {
                if busy[lane] || (lane == 2 && bulk >= 100) {
                    continue;
                }
                let Ok(permit) = self.lanes[lane].clone().try_acquire_owned() else {
                    continue;
                };
                let Some(row) = self.claim_one(db, &providers, lane).await else {
                    continue;
                };
                busy[lane] = true;
                count += 1;
                if lane == 2 {
                    bulk += 1;
                }
                let service = self.clone();
                let db = db.clone();
                let task = tasks.spawn(async move {
                    let _permit = permit;
                    let (id, device, user, event, payload, provider, created) = row;
                    service
                        .deliver(&db, &provider, id, device, user, event, payload, created)
                        .await;
                });
                owners.insert(task.id(), lane);
            }
            if tasks.is_empty() {
                break;
            }
            tokio::select! {
                joined = tasks.join_next_with_id() => {
                    if let Some(joined) = joined {
                        let id = match joined {
                            Ok((id, ())) => id,
                            Err(error) => {
                                tracing::warn!("push delivery task ended unexpectedly; lease retained");
                                error.id()
                            }
                        };
                        if let Some(lane) = owners.remove(&id) { busy[lane] = false; }
                    }
                }
                _ = tokio::time::sleep(Duration::from_secs(2)) => {}
            }
        }
        count
    }

    async fn claim_one(
        &self,
        db: &PgPool,
        providers: &[String],
        lane: usize,
    ) -> Option<(
        Uuid,
        Uuid,
        Uuid,
        String,
        serde_json::Value,
        String,
        chrono::DateTime<chrono::Utc>,
    )> {
        let lane_filter = match lane {
            0 => "provider::text='apns_voip'",
            1 => "provider::text='fcm' AND event_type IN ('incoming_call','call_ended')",
            2 => "provider::text='apns' OR(provider::text='fcm' AND event_type NOT IN ('incoming_call','call_ended'))",
            _ => return None,
        };
        let mut tx = db.begin().await.ok()?;
        let sql = format!("SELECT id,device_id,user_id,event_type,payload,provider::text,created_at FROM push_outbox \
            WHERE delivered_at IS NULL AND next_attempt_at<=now() AND provider::text=ANY($1) AND({lane_filter}) \
            ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
        let row: Option<(
            Uuid,
            Uuid,
            Uuid,
            String,
            serde_json::Value,
            String,
            chrono::DateTime<chrono::Utc>,
        )> = sqlx::query_as(&sql)
            .bind(providers)
            .fetch_optional(&mut *tx)
            .await
            .ok()?;
        if let Some(row) = row {
            sqlx::query("UPDATE push_outbox SET attempt_count=attempt_count+1,next_attempt_at=now()+($2*interval '1 second') WHERE id=$1")
                .bind(row.0).bind(LEASE_SECONDS).execute(&mut *tx).await.ok()?;
            tx.commit().await.ok()?;
            Some(row)
        } else {
            None
        }
    }

    async fn deliver(
        &self,
        db: &PgPool,
        provider_name: &str,
        outbox_id: Uuid,
        device_id: Uuid,
        user_id: Uuid,
        event_type: String,
        payload: serde_json::Value,
        created: chrono::DateTime<chrono::Utc>,
    ) {
        let mut event: PushEvent = match serde_json::from_value(payload) {
            Ok(event) => event,
            Err(err) => {
                tracing::error!(%err, "push worker: malformed outbox payload");
                let _ = self.finish(db, outbox_id, Some("malformed")).await;
                return;
            }
        };

        // Bind the recipient context into the payload so the client can reject
        // a DELAYED push that belongs to a previous account after an account
        // switch (the physical FCM token is shared across accounts).
        event.data.insert("user_id".to_string(), user_id.to_string());
        event.data.insert("device_id".to_string(), device_id.to_string());
        // A block may have committed after enqueueing or while FCM was offline.
        if !crate::users::blocks::allows_push(db, device_id, &event).await {
            let _ = self.finish(db, outbox_id, Some("privacy_restricted")).await;
            return;
        }

        if provider_name != "fcm" {
            self.deliver_apns(db, outbox_id, device_id, user_id, provider_name, &mut event, created).await;
            return;
        }
        let Some(provider) = &self.provider else { return; };

        // Resolve the current FCM token for this device at send time.
        let token: Option<(String, Uuid, i64)> = match sqlx::query_as(
            "SELECT token,registration_id,revision FROM push_tokens WHERE device_id = $1 AND provider='fcm' AND invalidated_at IS NULL",
        )
        .bind(device_id)
        .fetch_optional(db)
        .await
        {
            Ok(row) => row,
            Err(err) => {
                tracing::error!(%err, "push worker: token lookup failed");
                return;
            }
        };
        let Some((token, registration_id, revision)) = token else {
            // No active registration - nothing to send; terminal.
            let _ = self.finish(db, outbox_id, Some("no_active_token")).await;
            return;
        };

        match provider.send(&token, &event).await {
            Ok(PushSendOutcome::Delivered) => {
                let _ = sqlx::query(
                    "UPDATE push_tokens SET last_success_at = now(), last_error_code = NULL WHERE device_id = $1 AND provider='fcm' AND token=$2 AND registration_id=$3 AND revision=$4 AND invalidated_at IS NULL",
                )
                .bind(device_id)
                .bind(&token).bind(registration_id).bind(revision)
                .execute(db)
                .await;
                let _ = self.finish(db, outbox_id, None).await;
            }
            Ok(PushSendOutcome::InvalidToken) => {
                // Permanently invalid registration - invalidate and stop.
                let _ = sqlx::query("UPDATE push_tokens SET invalidated_at = now(), last_error_code = 'UNREGISTERED' WHERE device_id = $1 AND provider='fcm' AND token = $2 AND registration_id=$3 AND revision=$4 AND invalidated_at IS NULL")
                    .bind(device_id)
                    .bind(&token)
                    .bind(registration_id).bind(revision)
                    .execute(db)
                    .await;
                let _ = self.finish(db, outbox_id, Some("UNREGISTERED")).await;
            }
            Ok(PushSendOutcome::Transient) => {
                let _ = self.schedule_retry(db, outbox_id).await;
            }
            Ok(PushSendOutcome::Permanent) => {
                let _ = self.finish(db, outbox_id, Some("permanent")).await;
            }
            Err(err) => {
                tracing::warn!(%err, event_type, "push send failed");
                let _ = self.schedule_retry(db, outbox_id).await;
            }
        }
    }

    async fn deliver_apns(
        &self, db: &PgPool, outbox_id: Uuid, device_id: Uuid, user_id: Uuid,
        provider_name: &str, event: &mut PushEvent, created: chrono::DateTime<chrono::Utc>,
    ) {
        use super::apns::{Context, Environment, Kind, Outcome, Preferences, Target};
        use chrono::{Duration, Utc};
        let kind = match provider_name { "apns" => Kind::Alert, "apns_voip" => Kind::Voip, _ => return };
        if !self.providers_for(&event.event).iter().any(|p| p == provider_name) {
            let _ = self.finish(db, outbox_id, Some("apns_event_not_allowed")).await; return;
        }
        let row: Option<ApnsRow> = match sqlx::query_as(
            "SELECT p.token,p.environment,p.topic,p.registration_id,p.revision,p.token_registered_at, \
             p.message_alert_enabled,p.message_sound_enabled,p.permission_allowed \
             FROM push_tokens p JOIN devices d ON d.id=p.device_id JOIN users u ON u.id=d.user_id \
             WHERE p.device_id=$1 AND p.provider::text=$2 AND p.invalidated_at IS NULL AND p.token<>'' \
             AND d.user_id=$3 AND d.revoked_at IS NULL AND u.deleted_at IS NULL \
             AND EXISTS(SELECT 1 FROM sessions s WHERE s.device_id=d.id AND s.user_id=$3 AND s.revoked_at IS NULL AND s.expires_at>now()) \
             AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id=d.installation_id AND (b.until_date IS NULL OR b.until_date>now())) \
             AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id=$3 AND (b.until_date IS NULL OR b.until_date>now()))"
        ).bind(device_id).bind(provider_name).bind(user_id).fetch_optional(db).await {
            Ok(row) => row,
            Err(_) => { self.schedule_retry(db, outbox_id).await; return; }
        };
        let Some(row) = row else { let _ = self.finish(db, outbox_id, Some("no_active_apns_context")).await; return; };
        let environment = match row.environment.as_deref() {
            Some("production") => Environment::Production,
            Some("sandbox") => Environment::Sandbox,
            _ => { let _ = self.finish(db, outbox_id, Some("invalid_apns_context")).await; return; }
        };
        let Some(topic) = row.topic else { let _ = self.finish(db, outbox_id, Some("invalid_apns_context")).await; return; };
        let target = Target {
            kind, environment, topic, token: row.token, registration_id: row.registration_id,
            revision: row.revision, token_registered_at: row.token_registered_at,
            preferences: Preferences { message_alert_enabled: row.message_alert_enabled, message_sound_enabled: row.message_sound_enabled, permission_allowed: row.permission_allowed },
        };
        if kind == Kind::Alert && (!target.preferences.permission_allowed || (event.event == "new_message" && !target.preferences.message_alert_enabled)) {
            let _ = self.finish(db, outbox_id, Some("apns_alert_disabled")).await; return;
        }
        if event.event == "new_message" {
            let Some(chat) = event.data.get("chat_id").and_then(|id| Uuid::parse_str(id).ok()) else {
                let _ = self.finish(db, outbox_id, Some("malformed")).await; return;
            };
            let allowed = sqlx::query_scalar::<_, bool>(
                "SELECT NOT COALESCE((SELECT is_muted AND(muted_until IS NULL OR muted_until>now()) \
                 FROM chat_preferences WHERE chat_id=$1 AND user_id=$2),false)"
            ).bind(chat).bind(user_id).fetch_one(db).await.unwrap_or(false);
            if !allowed { let _ = self.finish(db, outbox_id, Some("chat_muted")).await; return; }
        }
        let now = Utc::now();
        let mut expires = created + Duration::seconds(i64::from(event.ttl_seconds.min(86400)));
        if kind == Kind::Voip {
            let Some(call) = event.data.get("call_id").and_then(|id| Uuid::parse_str(id).ok()) else {
                let _ = self.finish(db, outbox_id, Some("malformed")).await; return;
            };
            let live: Option<(chrono::DateTime<Utc>, String)> = match sqlx::query_as(
                "SELECT ringing_deadline,media_kind FROM calls WHERE id=$1 AND callee_user_id=$2 AND state='ringing' AND ringing_deadline>now()"
            ).bind(call).bind(user_id).fetch_optional(db).await {
                Ok(live) => live,
                Err(_) => { self.schedule_retry(db, outbox_id).await; return; }
            };
            let Some((deadline, media_kind)) = live else { let _ = self.finish(db, outbox_id, Some("call_no_longer_ringing")).await; return; };
            expires = expires.min(deadline);
            event.data.insert("media_kind".into(), media_kind);
        }
        if expires <= now { let _ = self.finish(db, outbox_id, Some("push_expired")).await; return; }
        let Some(provider) = self.apns.iter().find(|p| p.environment() == environment && p.allows(kind, &target.topic)) else {
            self.schedule_retry_after(db, outbox_id, 60).await; return;
        };
        let context = Context { push_id: outbox_id, user_id, device_id, sent_at_ms: now.timestamp_millis(), expires_at_ms: expires.timestamp_millis() };
        match provider.send(&target, event, &context).await {
            Ok(Outcome::Accepted) => {
                let _ = sqlx::query("UPDATE push_tokens SET last_success_at=now(),last_error_code=NULL \
                    WHERE device_id=$1 AND provider::text=$2 AND token=$3 AND registration_id=$4 AND revision=$5 AND invalidated_at IS NULL")
                    .bind(device_id).bind(provider_name).bind(&target.token).bind(target.registration_id).bind(target.revision).execute(db).await;
                let _ = self.finish(db, outbox_id, None).await;
            }
            Ok(Outcome::InvalidToken { invalid_since_ms }) => {
                let _ = sqlx::query("UPDATE push_tokens SET invalidated_at=now(),last_error_code='APNS_UNREGISTERED' \
                    WHERE device_id=$1 AND provider::text=$2 AND token=$3 AND registration_id=$4 AND revision=$5 AND invalidated_at IS NULL \
                    AND ($6::bigint IS NULL OR token_registered_at<=to_timestamp($6::double precision/1000.0))")
                    .bind(device_id).bind(provider_name).bind(&target.token).bind(target.registration_id).bind(target.revision).bind(invalid_since_ms).execute(db).await;
                let _ = self.finish(db, outbox_id, Some("APNS_UNREGISTERED")).await;
            }
            Ok(Outcome::Retry { minimum_delay_seconds }) => self.schedule_retry_after(db, outbox_id, minimum_delay_seconds).await,
            Ok(Outcome::Permanent) => { let _ = self.finish(db, outbox_id, Some("apns_provider_or_payload")).await; }
            Err(_) => self.schedule_retry(db, outbox_id).await,
        }
    }

    async fn finish(&self, db: &PgPool, outbox_id: Uuid, error_code: Option<&str>) -> Result<(), sqlx::Error> {
        sqlx::query("UPDATE push_outbox SET delivered_at = now(), last_error_code = $2 WHERE id = $1")
            .bind(outbox_id)
            .bind(error_code)
            .execute(db)
            .await?;
        Ok(())
    }

    /// Bounded exponential backoff; a row past MAX_ATTEMPTS becomes terminal
    /// (never retried forever).
    async fn schedule_retry(&self, db: &PgPool, outbox_id: Uuid) {
        self.schedule_retry_after(db, outbox_id, 0).await;
    }

    async fn schedule_retry_after(&self, db: &PgPool, outbox_id: Uuid, minimum: i64) {
        let _ = sqlx::query(
            "UPDATE push_outbox \
             SET next_attempt_at = now() + (GREATEST($3, LEAST(3600, 1 << LEAST(attempt_count, 10))) * interval '1 second'), \
                 delivered_at = CASE WHEN attempt_count >= $2 THEN now() ELSE delivered_at END, \
                 last_error_code = CASE WHEN attempt_count >= $2 THEN 'max_attempts' ELSE last_error_code END \
             WHERE id = $1",
        )
        .bind(outbox_id)
        .bind(MAX_ATTEMPTS)
        .bind(minimum)
        .execute(db)
        .await;
    }

    /// Spawns the periodic outbox worker (same `tokio::spawn` + interval
    /// pattern as the call sweep and attachment cleanup).
    pub fn spawn_worker(self: Arc<Self>, db: PgPool) {
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(2));
            loop {
                interval.tick().await;
                self.process_due(&db).await;
            }
        });
    }
}

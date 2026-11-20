use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use chrono::{DateTime, Utc};
use sqlx::PgPool;
use tokio::sync::{Notify, Semaphore};
use uuid::Uuid;

use crate::push::models::{Presentation, PushEvent, PushProvider, PushSendOutcome};

/// Backstop on attempts per row (bounded retry, never forever, Этап K.3).
/// Retries normally end earlier, at the row's deadline (`retry_deadline`);
/// this also bounds rows that keep being reclaimed after a crash mid-send.
pub const MAX_ATTEMPTS: i32 = 50;

/// A push is sent at most this long after it was queued (first attempt or
/// retry), or until the event expires when that comes first. Calls stop at
/// their ringing deadline.
pub const RETRY_WINDOW_SECONDS: i64 = 3600;

/// A background wake waits this long before it is sent, and later messages to
/// the same device fold into it meanwhile: one catch-up sync covers them all,
/// and iOS throttles frequent background pushes anyway.
pub const BACKGROUND_WAKE_DEBOUNCE_SECONDS: i64 = 60;

/// A message alert sent this long after it was queued (a retry, a backlog) no
/// longer rings: it is listed quietly instead.
const STALE_ALERT_SECONDS: i64 = 180;

/// Cap of the exponential backoff before jitter is added.
const MAX_BACKOFF_SECONDS: i64 = 600;

/// How long a claimed row is leased (in-flight) before another worker may
/// reclaim it after a crash mid-send.
const LEASE_SECONDS: i64 = 300;

/// Parallel APNs alert sends unless PUSH_APNS_CONCURRENCY says otherwise.
pub const DEFAULT_ALERT_CONCURRENCY: usize = 4;

/// Finished rows stay this long for diagnostics, then are purged: they hold
/// routing metadata (chat and message ids) that should not outlive the push.
const RETENTION_DAYS: i64 = 7;
/// Rows removed per DELETE, so no single statement holds locks for long.
const PURGE_BATCH: i64 = 5000;

// Independent lanes: a slow send on one never delays another. Background
// wakes have their own lane, so muted-chat traffic never delays a visible alert.
const LANE_VOIP: usize = 0;
const LANE_FCM_CALL: usize = 1;
const LANE_APNS_ALERT: usize = 2;
const LANE_FCM_BULK: usize = 3;
const LANE_APNS_BACKGROUND: usize = 4;
pub(crate) const LANES: usize = 5;
/// Ordinary (non-call) claims per sweep, so a sweep always ends.
const ORDINARY_PER_SWEEP: usize = 100;
/// How often every lane is polled, idle or mid-sweep: retries come due on
/// their own, without a wake.
const POLL_INTERVAL: Duration = Duration::from_secs(2);

#[derive(sqlx::FromRow)]
struct ApnsRow {
    token: String, environment: Option<String>, topic: Option<String>,
    registration_id: Uuid, revision: i64, token_registered_at: chrono::DateTime<chrono::Utc>,
    message_alert_enabled: bool, message_sound_enabled: bool, permission_allowed: bool,
}

/// One claimed outbox row. `attempt_count` already includes this attempt.
#[derive(sqlx::FromRow)]
struct Job {
    id: Uuid,
    device_id: Uuid,
    user_id: Uuid,
    event_type: String,
    payload: serde_json::Value,
    provider: String,
    created_at: DateTime<Utc>,
    attempt_count: i32,
}

/// Durable push dispatcher. When FCM is disabled (no credentials) it is a
/// no-op that never writes outbox rows - local dev stays clean.
#[derive(Clone)]
pub struct PushService {
    provider: Option<Arc<dyn PushProvider>>,
    apns: Vec<Arc<super::apns::Provider>>,
    lanes: [Arc<Semaphore>; LANES],
    limits: [usize; LANES],
    /// Shared by clones, so an enqueue on any clone wakes the one worker.
    wake: Arc<Notify>,
}

impl PushService {
    /// Snapshot the final routing capability before token invalidation/cascade.
    /// This queue deliberately has no account/device FK and contains no content
    /// or identity. Each route receives one wake, with bounded retries.
    pub async fn enqueue_session_check_transactional(&self, tx: &mut sqlx::Transaction<'_, sqlx::Postgres>, devices: &[Uuid]) -> Result<(), crate::error::AppError> {
        let mut providers = Vec::new();
        if self.provider.is_some() { providers.push("fcm"); }
        if !self.apns.is_empty() { providers.push("apns"); }
        if providers.is_empty() || devices.is_empty() { return Ok(()); }
        sqlx::query("INSERT INTO session_check_wakes(provider,token,environment,topic,registration_id,revision,token_registered_at) \
            SELECT DISTINCT ON(p.provider,p.token) p.provider::text,p.token,p.environment,p.topic,p.registration_id,p.revision,p.token_registered_at \
            FROM push_tokens p WHERE p.device_id=ANY($1) AND p.invalidated_at IS NULL AND p.token<>'' AND p.provider::text=ANY($2) \
            ORDER BY p.provider,p.token,p.revision DESC")
            .bind(devices).bind(&providers).execute(&mut **tx).await?;
        Ok(())
    }

    async fn process_session_checks(&self, db: &PgPool) -> usize {
        #[derive(sqlx::FromRow)]
        struct Wake { id: Uuid, provider: String, token: String, environment: Option<String>, topic: Option<String>,
            registration_id: Uuid, revision: i64, token_registered_at: DateTime<Utc>, expires_at: DateTime<Utc>, attempts: i32 }
        let _ = sqlx::query("DELETE FROM session_check_wakes WHERE expires_at<=now() OR attempts>=10").execute(db).await;
        let mut count = 0;
        // Bounded so ordinary traffic cannot be starved by a large moderation action.
        for _ in 0..10 {
            let row: Option<Wake> = sqlx::query_as("UPDATE session_check_wakes SET attempts=attempts+1,next_attempt_at=now()+interval '5 minutes' \
                WHERE id=(SELECT id FROM session_check_wakes WHERE next_attempt_at<=now() AND expires_at>now() AND attempts<10 \
                ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) \
                RETURNING id,provider,token,environment,topic,registration_id,revision,token_registered_at,expires_at,attempts")
                .fetch_optional(db).await.ok().flatten();
            let Some(row) = row else { break; };
            count += 1;
            let event = PushEvent::session_check();
            let terminal = if row.provider == "fcm" {
                match &self.provider {
                    Some(provider) => matches!(provider.send(&row.token, &event).await,
                        Ok(PushSendOutcome::Delivered | PushSendOutcome::InvalidToken | PushSendOutcome::Permanent)),
                    None => true,
                }
            } else {
                use super::apns::{Context, Environment, Kind, Outcome, Preferences, Target};
                let environment = match row.environment.as_deref() { Some("production") => Some(Environment::Production), Some("sandbox") => Some(Environment::Sandbox), _ => None };
                if let (Some(environment), Some(topic)) = (environment, row.topic) {
                    if let Some(provider) = self.apns.iter().find(|p| p.environment()==environment && p.allows(Kind::Alert, &topic)) {
                        let target = Target { kind:Kind::Alert, environment, topic, token:row.token, registration_id:row.registration_id,
                            revision:row.revision, token_registered_at:row.token_registered_at,
                            preferences:Preferences { message_alert_enabled:false,message_sound_enabled:false,permission_allowed:false } };
                        let context = Context { push_id:row.id, user_id:Uuid::nil(), device_id:Uuid::nil(), sent_at_ms:Utc::now().timestamp_millis(), expires_at_ms:row.expires_at.timestamp_millis() };
                        match provider.send(&target,&event,&context).await {
                            Ok(Outcome::Accepted | Outcome::InvalidToken { .. } | Outcome::Permanent { .. } | Outcome::Expired) => true,
                            Err(error) => !error.is_retryable(),
                            _ => false,
                        }
                    } else { true }
                } else { true }
            };
            if terminal || row.attempts>=10 {
                let _ = sqlx::query("DELETE FROM session_check_wakes WHERE id=$1").bind(row.id).execute(db).await;
            }
        }
        count
    }
    fn build(provider: Option<Arc<dyn PushProvider>>) -> Self {
        let mut limits = [1; LANES];
        limits[LANE_APNS_ALERT] = DEFAULT_ALERT_CONCURRENCY;
        Self {
            provider,
            apns: Vec::new(),
            lanes: std::array::from_fn(|lane| Arc::new(Semaphore::new(limits[lane]))),
            limits,
            wake: Arc::new(Notify::new()),
        }
    }

    pub fn disabled() -> Self {
        Self::build(None)
    }

    pub fn new(provider: Arc<dyn PushProvider>) -> Self {
        Self::build(Some(provider))
    }

    pub fn enabled(&self) -> bool {
        self.provider.is_some() || !self.apns.is_empty()
    }

    pub fn with_apns(mut self, providers: Vec<Arc<super::apns::Provider>>) -> Self {
        self.apns = providers;
        self
    }

    /// How many APNs alerts may be in flight at once (at least one). Call it
    /// while building the service: clones made earlier keep the old limit.
    pub fn with_alert_concurrency(mut self, limit: usize) -> Self {
        let limit = limit.max(1);
        self.limits[LANE_APNS_ALERT] = limit;
        self.lanes[LANE_APNS_ALERT] = Arc::new(Semaphore::new(limit));
        self
    }

    /// Wakes the worker so rows committed just now go out without waiting for
    /// the next poll. Call only after the transaction that queued them commits.
    pub fn wake_worker(&self) {
        self.wake.notify_one();
    }

    pub fn admits_apns(&self, kind: super::apns::Kind, environment: super::apns::Environment, topic: &str) -> bool {
        self.apns.iter().any(|p| p.environment() == environment && p.allows(kind, topic))
    }

    /// Providers whose tokens an `event` push goes to; empty when push is off.
    pub(crate) fn providers_for(&self, event: &str) -> Vec<String> {
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
    ///
    /// This runs after the caller's transaction has committed, so it is best
    /// effort: a crash or database error between that commit and this insert
    /// loses the push, and the failure is only logged. (Migration 0007 says the
    /// outbox closes that gap; it does so only for pushes queued inside the
    /// triggering transaction, as `enqueue_message_transactional` does for new
    /// messages. The old migration text cannot change: sqlx checksums it.)
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
        let (mut devices, mut users, mut row_providers) = (Vec::new(), Vec::new(), Vec::new());
        let mut unchecked = 0usize;
        for (device, user, provider) in rows {
            match crate::users::blocks::allows_push(db, device, event).await {
                Ok(true) => {}
                Ok(false) => continue,
                // Queue it anyway: delivery checks the block again and retries
                // a failed check, so an outage never drops the push silently.
                Err(_) => unchecked += 1,
            }
            devices.push(device);
            users.push(user);
            row_providers.push(provider);
        }
        if unchecked > 0 {
            tracing::warn!(event_type = %event.event, unchecked, "push block check failed at enqueue; delivery re-checks");
        }
        if devices.is_empty() {
            return;
        }
        // One statement, so the rows land together or the failure is logged
        // once (per-row inserts in a transaction turned the first error into a
        // silent rollback of every later row).
        match sqlx::query(
            "INSERT INTO push_outbox (event_type, user_id, device_id, payload, provider) \
             SELECT $1, t.user_id, t.device_id, $2, t.provider::push_provider \
             FROM unnest($3::uuid[], $4::uuid[], $5::text[]) AS t(device_id, user_id, provider)",
        )
        .bind(&event.event)
        .bind(&payload)
        .bind(&devices)
        .bind(&users)
        .bind(&row_providers)
        .execute(db)
        .await
        {
            Ok(_) => self.wake_worker(),
            Err(err) => tracing::error!(event_type = %event.event, rows = devices.len(), error = %err,
                "failed to enqueue push outbox rows"),
        }
    }

    /// Queue an administrative notice in the caller's transaction. A failure rolls
    /// back the notice and audit too, so the UI can safely report the outcome.
    /// The caller calls `wake_worker` once its transaction has committed.
    pub async fn enqueue_incoming_call_transactional(
        &self, tx: &mut sqlx::Transaction<'_, sqlx::Postgres>, user_id: Uuid, event: &PushEvent,
    ) -> Result<u64, crate::error::AppError> {
        if !self.enabled() { return Ok(0); }
        let payload = serde_json::to_value(event).map_err(|_| crate::error::AppError::Internal)?;
        let result = sqlx::query(
            "INSERT INTO push_outbox(event_type,user_id,device_id,payload,provider) \
             SELECT $1,d.user_id,d.id,$2,pt.provider FROM devices d JOIN push_tokens pt ON pt.device_id=d.id \
             WHERE d.user_id=$3 AND d.revoked_at IS NULL AND pt.invalidated_at IS NULL AND pt.provider::text=ANY($4) \
             AND NOT user_device_call_blocked(d.id,$5)",
        ).bind(&event.event).bind(payload).bind(user_id).bind(self.providers_for(&event.event))
            .bind(event.data.get("call_id").and_then(|id| Uuid::parse_str(id).ok()))
            .execute(&mut **tx).await?;
        Ok(result.rows_affected())
    }

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

    /// Queues a chat message push in the sender's transaction, so the push
    /// commits or rolls back with the message: a failure fails the send (the
    /// client retries it) and a duplicate send, which inserts no message, never
    /// queues twice. The caller wakes the worker after its commit.
    ///
    /// One statement covers the live-token devices of `alert_users` plus the
    /// `quiet_devices`, never a device a block hides the chat from or a user
    /// who blocked the chat. Each row gets its presentation:
    /// - a user who muted the chat, or an APNs device with message alerts or
    ///   notification permission off: `Background` (a wake that lets the app
    ///   sync and acknowledge delivery, no notification);
    /// - a quiet device (an earlier album item already alerted it): `Quiet`;
    /// - otherwise `Alert`.
    ///
    /// Background wakes are debounced per device: a new one waits
    /// `BACKGROUND_WAKE_DEBOUNCE_SECONDS`, and while it waits unsent, later
    /// messages update it to the newest message instead of adding rows. Its
    /// row is locked here (SKIP LOCKED), so the worker cannot send it before
    /// this message commits; a row the worker holds is left alone and a new
    /// one is queued.
    ///
    /// Quiet and background rows are APNs only. FCM keeps exactly the rows it
    /// had: one alert per unmuted recipient device, nothing for album items
    /// after the first. Returns the number of new rows.
    pub async fn enqueue_message_transactional(
        &self,
        tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        chat_id: Uuid,
        alert_users: &[Uuid],
        quiet_devices: &[Uuid],
        event: &PushEvent,
    ) -> Result<u64, crate::error::AppError> {
        if !self.enabled() || (alert_users.is_empty() && quiet_devices.is_empty()) {
            return Ok(0);
        }
        // Serialized here, so serde stays the only definition of the stored shape.
        let encode = |presentation| {
            serde_json::to_value(event.clone().with_presentation(presentation)).map_err(|_| crate::error::AppError::Internal)
        };
        let (alert, quiet, background) =
            (encode(Presentation::Alert)?, encode(Presentation::Quiet)?, encode(Presentation::Background)?);
        let result = sqlx::query(
            "WITH t AS ( \
                 SELECT d.user_id, d.id AS device_id, pt.provider, \
                        CASE WHEN cp.is_muted AND (cp.muted_until IS NULL OR cp.muted_until > now()) THEN 'background' \
                             WHEN pt.provider::text = 'apns' AND NOT (pt.permission_allowed AND pt.message_alert_enabled) THEN 'background' \
                             WHEN d.id = ANY($6) THEN 'quiet' ELSE 'alert' END AS presentation \
                 FROM devices d \
                 JOIN push_tokens pt ON pt.device_id = d.id \
                 LEFT JOIN chat_preferences cp ON cp.chat_id = $7 AND cp.user_id = d.user_id \
                 WHERE (d.user_id = ANY($5) OR d.id = ANY($6)) \
                   AND d.revoked_at IS NULL AND pt.invalidated_at IS NULL \
                   AND pt.provider::text = ANY($8) \
                   AND NOT user_device_chat_blocked(d.id, $7) \
                   AND cp.is_blocked IS NOT TRUE), \
             folded AS ( \
                 UPDATE push_outbox SET payload = $4 \
                 WHERE id IN (SELECT o.id FROM push_outbox o \
                              JOIN t ON t.device_id = o.device_id AND t.presentation = 'background' AND t.provider::text = 'apns' \
                              WHERE o.provider = 'apns' AND o.delivered_at IS NULL AND o.attempt_count = 0 \
                                AND o.event_type = $1 AND o.payload->>'presentation' = 'background' \
                              FOR UPDATE OF o SKIP LOCKED) \
                 RETURNING device_id) \
             INSERT INTO push_outbox (event_type, user_id, device_id, payload, provider, next_attempt_at) \
             SELECT $1, t.user_id, t.device_id, \
                    CASE t.presentation WHEN 'background' THEN $4 WHEN 'quiet' THEN $3 ELSE $2 END, t.provider, \
                    CASE t.presentation WHEN 'background' THEN now() + ($9 * interval '1 second') ELSE now() END \
             FROM t \
             WHERE (t.presentation = 'alert' OR t.provider::text = 'apns') \
               AND NOT (t.presentation = 'background' AND t.device_id IN (SELECT device_id FROM folded))",
        )
        .bind(&event.event)
        .bind(alert)
        .bind(quiet)
        .bind(background)
        .bind(alert_users)
        .bind(quiet_devices)
        .bind(chat_id)
        .bind(self.providers_for(&event.event))
        .bind(BACKGROUND_WAKE_DEBOUNCE_SECONDS)
        .execute(&mut **tx)
        .await;
        match result {
            Ok(result) => Ok(result.rows_affected()),
            Err(err) => {
                tracing::warn!(event_type = %event.event, "message push could not be queued; the send is rolled back");
                Err(err.into())
            }
        }
    }

    /// One sweep over due outbox rows. Claims with `FOR UPDATE SKIP LOCKED`
    /// (concurrency-safe: two workers never deliver the same row), leases each
    /// row forward in the same statement so a crash mid-send eventually
    /// retries, then dispatches outside it. Rows queued (a wake) or coming due
    /// (the poll) while sends are in flight join the same sweep, which ends
    /// once nothing is in flight and a poll of every lane finds nothing due.
    /// Returns how many rows were claimed.
    pub async fn process_due(&self, db: &PgPool) -> usize {
        let (checks, ordinary) = tokio::join!(self.process_session_checks(db), self.sweep(db));
        checks + ordinary.0
    }

    /// `process_due`, plus whether the sweep stopped at `ORDINARY_PER_SWEEP`
    /// (more rows are probably due, so the worker sweeps again at once).
    async fn sweep(&self, db: &PgPool) -> (usize, bool) {
        if !self.enabled() {
            return (0, false);
        }
        // One worker, independent bounded lanes. A held ordinary request
        // cannot prevent the free call lanes from being polled. Permits are
        // shared by service clones and drop on cancellation/panic as well.
        let mut tasks = tokio::task::JoinSet::new();
        let mut owners = HashMap::new();
        let mut in_flight = [0usize; LANES];
        let mut count = 0;
        let mut ordinary = 0;
        // Lanes the next pass claims for. A finished send refills only its own
        // lane: nothing else changed, and each claim is a database round trip,
        // so polling every lane after every send made a backlog drain at the
        // pace of five claims per row. Every lane is polled at the start, on a
        // wake and on the poll; before the sweep ends, one query checks that
        // no lane has a row due.
        let mut pending = [true; LANES];
        let mut poll = tokio::time::interval_at(tokio::time::Instant::now() + POLL_INTERVAL, POLL_INTERVAL);
        poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            let this_pass = std::mem::take(&mut pending);
            let every_lane = this_pass.iter().all(|lane| *lane);
            for lane in (0..LANES).filter(|lane| this_pass[*lane] && self.serves(*lane)) {
                let bulk = !matches!(lane, LANE_VOIP | LANE_FCM_CALL);
                // Fill the lane up to its limit; an empty claim ends it for this pass.
                while in_flight[lane] < self.limits[lane] && !(bulk && ordinary >= ORDINARY_PER_SWEEP) {
                    let Ok(permit) = self.lanes[lane].clone().try_acquire_owned() else {
                        break;
                    };
                    let Some(job) = self.claim_one(db, lane).await else {
                        break;
                    };
                    in_flight[lane] += 1;
                    count += 1;
                    if bulk {
                        ordinary += 1;
                    }
                    let service = self.clone();
                    let db = db.clone();
                    let task = tasks.spawn(async move {
                        let _permit = permit;
                        service.deliver(&db, job).await;
                    });
                    owners.insert(task.id(), lane);
                }
            }
            if tasks.is_empty() {
                // Nothing in flight. End once every lane has been polled with
                // nothing due: a lane this pass skipped may have a retry that
                // came due since the last poll, so ask once for all of them.
                if every_lane || !self.any_due(db).await {
                    break;
                }
                pending = [true; LANES];
                continue;
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
                        if let Some(lane) = owners.remove(&id) {
                            in_flight[lane] -= 1;
                            pending[lane] = true;
                        }
                    }
                }
                // A row queued while sends are in flight (a call during a
                // group fan-out) is claimed now, not at the next poll.
                _ = self.wake.notified() => pending = [true; LANES],
                // Retries come due without a wake. A fixed cadence, so a
                // steady stream of finished sends cannot postpone it.
                _ = poll.tick() => pending = [true; LANES],
            }
        }
        (count, ordinary >= ORDINARY_PER_SWEEP)
    }

    /// Whether this service sends the rows of `lane` at all.
    fn serves(&self, lane: usize) -> bool {
        match lane {
            LANE_FCM_CALL | LANE_FCM_BULK => self.provider.is_some(),
            _ => !self.apns.is_empty(),
        }
    }

    /// Claims the oldest due row of `lane` and leases it, in one statement:
    /// one round trip, whether a row is claimed or the lane is empty. The
    /// predicates compare `provider` with the enum literal, so
    /// `push_outbox_pending_idx` walks one provider in claim order instead of
    /// scanning every due row.
    async fn claim_one(&self, db: &PgPool, lane: usize) -> Option<Job> {
        let lane_filter = lane_filter(lane)?;
        // The target is aliased, so `push_outbox` in the lane filter names
        // the candidate row of the subquery.
        let sql = format!("UPDATE push_outbox AS claimed \
            SET attempt_count=claimed.attempt_count+1,next_attempt_at=now()+($1*interval '1 second') \
            WHERE claimed.id=(SELECT id FROM push_outbox \
                WHERE delivered_at IS NULL AND next_attempt_at<=now() AND ({lane_filter}) \
                ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) \
            RETURNING claimed.id,claimed.device_id,claimed.user_id,claimed.event_type,claimed.payload, \
                claimed.provider::text AS provider,claimed.created_at,claimed.attempt_count");
        sqlx::query_as(&sql).bind(LEASE_SECONDS).fetch_optional(db).await.ok().flatten()
    }

    /// Whether a lane this service sends has a row due now, by the claim
    /// predicates: one query instead of one claim per lane. A row a sender's
    /// open transaction holds counts as due; the pass that follows claims
    /// nothing and the sweep ends. An error counts as due for the same reason.
    async fn any_due(&self, db: &PgPool) -> bool {
        let lanes = (0..LANES)
            .filter(|lane| self.serves(*lane))
            .filter_map(lane_filter)
            .map(|filter| format!("({filter})"))
            .collect::<Vec<_>>()
            .join(" OR ");
        if lanes.is_empty() {
            return false;
        }
        let sql = format!("SELECT EXISTS(SELECT 1 FROM push_outbox \
            WHERE delivered_at IS NULL AND next_attempt_at<=now() AND ({lanes}))");
        sqlx::query_scalar(&sql).fetch_one(db).await.unwrap_or(true)
    }

    async fn deliver(&self, db: &PgPool, mut job: Job) {
        let mut event: PushEvent = match serde_json::from_value(std::mem::take(&mut job.payload)) {
            Ok(event) => event,
            Err(err) => {
                tracing::error!(%err, "push worker: malformed outbox payload");
                let _ = self.finish(db, job.id, Some("malformed")).await;
                return;
            }
        };
        if job.attempt_count > MAX_ATTEMPTS {
            // Only reachable through repeated lease expiry (a crash or hang
            // mid-send); scheduled retries end earlier in `schedule_retry`.
            tracing::warn!(outbox_id = %job.id, event_type = %job.event_type, attempt = job.attempt_count,
                "push dropped at the attempt backstop");
            let _ = self.finish(db, job.id, Some("max_attempts")).await;
            return;
        }
        let deadline = retry_deadline(job.created_at, event.ttl_seconds);
        if Utc::now() > deadline {
            // Checked before every send, not only when scheduling a retry: a row
            // back from an expired lease (a crash or panic mid-send) or claimed
            // late from a backlog must not go out stale. The failure that kept
            // it pending stays on the row.
            tracing::warn!(outbox_id = %job.id, event_type = %job.event_type, attempt = job.attempt_count,
                "push dropped at its deadline");
            let _ = self.finish_keeping_code(db, job.id, "push_expired").await;
            return;
        }

        // Bind the recipient context into the payload so the client can reject
        // a DELAYED push that belongs to a previous account after an account
        // switch (the physical FCM token is shared across accounts).
        event.data.insert("user_id".to_string(), job.user_id.to_string());
        event.data.insert("device_id".to_string(), job.device_id.to_string());
        // A block may have committed after enqueueing or while FCM was offline.
        match crate::users::blocks::allows_push(db, job.device_id, &event).await {
            Ok(true) => {}
            Ok(false) => {
                let _ = self.finish(db, job.id, Some("privacy_restricted")).await;
                return;
            }
            Err(err) => {
                // Not the user's choice: try again rather than drop the push.
                tracing::warn!(outbox_id = %job.id, event_type = %job.event_type, error = %err, "push block check failed; retrying");
                self.schedule_retry(db, &job, "db_error", 0, deadline).await;
                return;
            }
        }

        if job.provider != "fcm" {
            self.deliver_apns(db, &job, &mut event, deadline).await;
            return;
        }
        let Some(provider) = &self.provider else { return; };
        let device_id = job.device_id;

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
                // Retry within the deadline; returning here would leave the row
                // to come back only after its lease expires.
                tracing::warn!(outbox_id = %job.id, event_type = %job.event_type, error = %err, "FCM token lookup failed; retrying");
                self.schedule_retry(db, &job, "db_error", 0, deadline).await;
                return;
            }
        };
        let Some((token, registration_id, revision)) = token else {
            // No active registration - nothing to send; terminal.
            let _ = self.finish(db, job.id, Some("no_active_token")).await;
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
                let _ = self.finish(db, job.id, None).await;
            }
            Ok(PushSendOutcome::InvalidToken) => {
                // Permanently invalid registration - invalidate and stop.
                let _ = sqlx::query("UPDATE push_tokens SET invalidated_at = now(), last_error_code = 'UNREGISTERED' WHERE device_id = $1 AND provider='fcm' AND token = $2 AND registration_id=$3 AND revision=$4 AND invalidated_at IS NULL")
                    .bind(device_id)
                    .bind(&token)
                    .bind(registration_id).bind(revision)
                    .execute(db)
                    .await;
                let _ = self.finish(db, job.id, Some("UNREGISTERED")).await;
            }
            Ok(PushSendOutcome::Transient) => {
                self.schedule_retry(db, &job, "fcm_transient", 0, deadline).await;
            }
            Ok(PushSendOutcome::Permanent) => {
                let _ = self.finish(db, job.id, Some("permanent")).await;
            }
            Err(err) => {
                tracing::warn!(%err, event_type = %job.event_type, "push send failed");
                self.schedule_retry(db, &job, "fcm_error", 0, deadline).await;
            }
        }
    }

    async fn deliver_apns(&self, db: &PgPool, job: &Job, event: &mut PushEvent, deadline: DateTime<Utc>) {
        use super::apns::{Context, Environment, Kind, Outcome, Preferences, Target};
        use chrono::Duration;
        let (outbox_id, device_id, user_id, provider_name) = (job.id, job.device_id, job.user_id, job.provider.as_str());
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
             AND NOT EXISTS(SELECT 1 FROM installation_suspensions b WHERE b.installation_id IN (SELECT d.installation_id UNION SELECT va.client_installation_id FROM verified_installation_aliases va WHERE va.installation_id=d.verified_installation_id) AND (b.until_date IS NULL OR b.until_date>now())) \
             AND NOT EXISTS(SELECT 1 FROM server_suspensions b WHERE b.user_id=$3 AND (b.until_date IS NULL OR b.until_date>now()))"
        ).bind(device_id).bind(provider_name).bind(user_id).fetch_optional(db).await {
            Ok(row) => row,
            Err(err) => {
                tracing::warn!(outbox_id = %outbox_id, event_type = %job.event_type, error = %err, "APNs registration lookup failed; retrying");
                self.schedule_retry(db, job, "db_error", 0, deadline).await; return;
            }
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
        if event.event == "new_message" {
            let Some(chat) = event.data.get("chat_id").and_then(|id| Uuid::parse_str(id).ok()) else {
                let _ = self.finish(db, outbox_id, Some("malformed")).await; return;
            };
            let message = event.data.get("message_id").and_then(|id| Uuid::parse_str(id).ok());
            // Read at send time: a block, mute or read may have committed after
            // the row was queued (a retry can come much later).
            let (blocked, muted, read) = match sqlx::query_as::<_, (bool, bool, bool)>(
                "SELECT COALESCE(bool_or(is_blocked), false), \
                        COALESCE(bool_or(is_muted AND (muted_until IS NULL OR muted_until > now())), false), \
                        EXISTS(SELECT 1 FROM message_receipts WHERE message_id=$3 AND user_id=$2 AND read_at IS NOT NULL) \
                 FROM chat_preferences WHERE chat_id=$1 AND user_id=$2"
            ).bind(chat).bind(user_id).bind(message).fetch_one(db).await {
                Ok(state) => state,
                Err(err) => {
                    // A failed lookup is neither a mute nor a block: retry.
                    tracing::warn!(outbox_id = %outbox_id, event_type = %job.event_type, error = %err, "chat preference lookup failed; retrying");
                    self.schedule_retry(db, job, "db_error", 0, deadline).await; return;
                }
            };
            if blocked { let _ = self.finish(db, outbox_id, Some("privacy_restricted")).await; return; }
            // The user already read it (a read implies the app synced): no
            // notification and no wake is needed any more.
            if read { let _ = self.finish(db, outbox_id, Some("already_read")).await; return; }
            if kind == Kind::Alert {
                let alerts_off = !target.preferences.permission_allowed || !target.preferences.message_alert_enabled;
                let waited = (Utc::now() - job.created_at).num_seconds();
                event.presentation = send_presentation(event.presentation, muted, alerts_off, waited);
            }
        } else if kind == Kind::Alert && !target.preferences.permission_allowed {
            let _ = self.finish(db, outbox_id, Some("apns_alert_disabled")).await; return;
        }
        let now = Utc::now();
        let mut expires = job.created_at + Duration::seconds(i64::from(event.ttl_seconds.min(86400)));
        if kind == Kind::Voip {
            let Some(call) = event.data.get("call_id").and_then(|id| Uuid::parse_str(id).ok()) else {
                let _ = self.finish(db, outbox_id, Some("malformed")).await; return;
            };
            let live: Option<(chrono::DateTime<Utc>, String)> = match sqlx::query_as(
                "SELECT ringing_deadline,media_kind FROM calls WHERE id=$1 AND callee_user_id=$2 AND state='ringing' AND ringing_deadline>now()"
            ).bind(call).bind(user_id).fetch_optional(db).await {
                Ok(live) => live,
                Err(err) => {
                    tracing::warn!(outbox_id = %outbox_id, event_type = %job.event_type, error = %err, "call lookup failed; retrying");
                    self.schedule_retry(db, job, "db_error", 0, deadline).await; return;
                }
            };
            let Some((ringing_deadline, media_kind)) = live else { let _ = self.finish(db, outbox_id, Some("call_no_longer_ringing")).await; return; };
            expires = expires.min(ringing_deadline);
            event.data.insert("media_kind".into(), media_kind);
        }
        // Never schedule a retry for a moment the push is already dead.
        let deadline = deadline.min(expires);
        if expires <= now { let _ = self.finish(db, outbox_id, Some("push_expired")).await; return; }
        let env = environment.as_str();
        let Some(provider) = self.apns.iter().find(|p| p.environment() == environment && p.allows(kind, &target.topic)) else {
            // The registration names an environment or topic this server no
            // longer serves: configuration, not the device.
            log_failure(job, env, event.presentation, "apns_no_provider", None, false, "no APNs provider for this registration; retrying");
            self.record_token_error(db, job, &target, "apns_no_provider").await;
            self.schedule_retry(db, job, "apns_no_provider", 60, deadline).await; return;
        };
        let context = Context { push_id: outbox_id, user_id, device_id, sent_at_ms: now.timestamp_millis(), expires_at_ms: expires.timestamp_millis() };
        match provider.send(&target, event, &context).await {
            Ok(Outcome::Accepted) => {
                let _ = sqlx::query("UPDATE push_tokens SET last_success_at=now(),last_error_code=NULL \
                    WHERE device_id=$1 AND provider::text=$2 AND token=$3 AND registration_id=$4 AND revision=$5 AND invalidated_at IS NULL")
                    .bind(device_id).bind(provider_name).bind(&target.token).bind(target.registration_id).bind(target.revision).execute(db).await;
                let _ = self.finish(db, outbox_id, None).await;
            }
            Ok(Outcome::InvalidToken { invalid_since_ms, rejection }) => {
                let code = rejection.code();
                log_failure(job, env, event.presentation, &code, Some(rejection), false, "APNs rejected the device token");
                let _ = sqlx::query("UPDATE push_tokens SET invalidated_at=now(),last_error_code=$7 \
                    WHERE device_id=$1 AND provider::text=$2 AND token=$3 AND registration_id=$4 AND revision=$5 AND invalidated_at IS NULL \
                    AND ($6::bigint IS NULL OR token_registered_at<=to_timestamp($6::double precision/1000.0))")
                    .bind(device_id).bind(provider_name).bind(&target.token).bind(target.registration_id).bind(target.revision).bind(invalid_since_ms)
                    .bind(&code).execute(db).await;
                let _ = self.finish(db, outbox_id, Some(&code)).await;
            }
            Ok(Outcome::Retry { minimum_delay_seconds, rejection }) => {
                let code = rejection.code();
                if rejection.is_provider_auth() {
                    log_failure(job, env, event.presentation, &code, Some(rejection), true,
                        "APNs refused our provider token; check the key, key id, team id and environment");
                } else {
                    log_failure(job, env, event.presentation, &code, Some(rejection), false, "APNs asked to retry the push");
                }
                self.record_token_error(db, job, &target, &code).await;
                self.schedule_retry(db, job, &code, minimum_delay_seconds, deadline).await;
            }
            Ok(Outcome::Permanent { rejection }) => {
                let code = rejection.code();
                log_failure(job, env, event.presentation, &code, Some(rejection), false, "APNs rejected the push");
                self.record_token_error(db, job, &target, &code).await;
                let _ = self.finish(db, outbox_id, Some(&code)).await;
            }
            Ok(Outcome::Expired) => { let _ = self.finish(db, outbox_id, Some("push_expired")).await; }
            Err(error) => {
                let code = error.code();
                let what = if error.is_retryable() { "APNs send failed; retrying" } else { "APNs send failed; not retryable" };
                log_failure(job, env, event.presentation, code, None, error.needs_operator(), what);
                self.record_token_error(db, job, &target, code).await;
                if error.is_retryable() {
                    self.schedule_retry(db, job, code, 0, deadline).await;
                } else {
                    let _ = self.finish(db, outbox_id, Some(code)).await;
                }
            }
        }
    }

    /// Keeps the latest failure on the registration for admin diagnostics.
    /// Same guard as the success path, so a newer registration is untouched.
    async fn record_token_error(&self, db: &PgPool, job: &Job, target: &super::apns::Target, code: &str) {
        let _ = sqlx::query("UPDATE push_tokens SET last_error_code=$6 \
            WHERE device_id=$1 AND provider::text=$2 AND token=$3 AND registration_id=$4 AND revision=$5 AND invalidated_at IS NULL")
            .bind(job.device_id).bind(&job.provider).bind(&target.token).bind(target.registration_id).bind(target.revision)
            .bind(code).execute(db).await;
    }

    async fn finish(&self, db: &PgPool, outbox_id: Uuid, error_code: Option<&str>) -> Result<(), sqlx::Error> {
        sqlx::query("UPDATE push_outbox SET delivered_at = now(), last_error_code = $2 WHERE id = $1")
            .bind(outbox_id)
            .bind(error_code)
            .execute(db)
            .await?;
        Ok(())
    }

    /// Ends a row without sending it. A failure code already on the row (why
    /// it was still pending) is kept; `fallback` is used only when there is none.
    async fn finish_keeping_code(&self, db: &PgPool, outbox_id: Uuid, fallback: &str) -> Result<(), sqlx::Error> {
        sqlx::query("UPDATE push_outbox SET delivered_at = now(), last_error_code = COALESCE(last_error_code, $2) WHERE id = $1")
            .bind(outbox_id)
            .bind(fallback)
            .execute(db)
            .await?;
        Ok(())
    }

    /// Schedules the next attempt with capped, jittered backoff, or ends the
    /// row when that attempt would land after its deadline. The failure code
    /// stays on the row either way, so a finished row says why it failed.
    async fn schedule_retry(&self, db: &PgPool, job: &Job, code: &str, minimum_delay_seconds: i64, deadline: DateTime<Utc>) {
        match plan_retry(job.attempt_count, minimum_delay_seconds, rand::random::<f64>(), Utc::now(), deadline) {
            RetryPlan::After(seconds) => {
                let _ = sqlx::query(
                    "UPDATE push_outbox SET next_attempt_at = now() + ($2 * interval '1 second'), last_error_code = $3 \
                     WHERE id = $1 AND delivered_at IS NULL",
                )
                .bind(job.id)
                .bind(seconds)
                .bind(code)
                .execute(db)
                .await;
            }
            RetryPlan::GiveUp(why) => {
                tracing::warn!(outbox_id = %job.id, event_type = %job.event_type, code, why, attempt = job.attempt_count,
                    "push retries exhausted");
                let _ = self.finish(db, job.id, Some(code)).await;
            }
        }
    }

    /// Spawns the outbox worker. It sweeps when an enqueue wakes it and every
    /// 2 s regardless, which picks up scheduled retries. A sweep that stopped
    /// at its per-sweep cap is followed by another at once, so a backlog
    /// drains without pausing for the poll.
    pub fn spawn_worker(self: Arc<Self>, db: PgPool) -> tokio::task::JoinHandle<()> {
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(POLL_INTERVAL);
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            let mut more_due = false;
            loop {
                if !more_due {
                    tokio::select! {
                        _ = interval.tick() => {}
                        _ = self.wake.notified() => {}
                    }
                }
                let (_, ordinary) = tokio::join!(self.process_session_checks(&db), self.sweep(&db));
                more_due = ordinary.1;
            }
        })
    }
}

/// The rows a lane claims, as a predicate on `push_outbox`.
///
/// APNs alerts (and quiet album items) of one device go out strictly in queue
/// order: a row waits while an older alert row of its device is unfinished,
/// whether in flight or waiting for a retry. Alerts of a chat share a collapse
/// id, so an older one sent late would replace the newer notification. A
/// device's background wakes are not ordered against its alerts (they use
/// their own collapse id), and calls never wait behind an older call.
fn lane_filter(lane: usize) -> Option<&'static str> {
    Some(match lane {
        LANE_VOIP => "provider='apns_voip'",
        LANE_FCM_CALL => "provider='fcm' AND event_type IN ('incoming_call','call_ended')",
        LANE_APNS_ALERT => "provider='apns' AND (payload->>'presentation') IS DISTINCT FROM 'background' \
            AND NOT EXISTS (SELECT 1 FROM push_outbox older \
                WHERE older.device_id=push_outbox.device_id AND older.provider='apns' AND older.delivered_at IS NULL \
                AND (older.payload->>'presentation') IS DISTINCT FROM 'background' \
                AND (older.created_at,older.id)<(push_outbox.created_at,push_outbox.id))",
        LANE_FCM_BULK => "provider='fcm' AND event_type NOT IN ('incoming_call','call_ended')",
        LANE_APNS_BACKGROUND => "provider='apns' AND (payload->>'presentation')='background'",
        _ => return None,
    })
}

/// One log line per failed attempt: ids and enum codes only, never the token.
fn log_failure(job: &Job, environment: &str, presentation: Presentation, code: &str,
    rejection: Option<super::apns::Rejection>, operator: bool, what: &str) {
    let presentation = presentation.as_str();
    let status = rejection.map(|r| r.status);
    let reason = rejection.map(|r| r.reason.as_str());
    if operator {
        tracing::error!(outbox_id = %job.id, event_type = %job.event_type, provider = %job.provider, environment,
            presentation, status, reason, code, attempt = job.attempt_count, "{what}");
    } else {
        tracing::warn!(outbox_id = %job.id, event_type = %job.event_type, provider = %job.provider, environment,
            presentation, status, reason, code, attempt = job.attempt_count, "{what}");
    }
}

/// How a message row is sent on an APNs alert registration. Never louder than
/// it was queued as. A muted chat, or a device that shows no message alerts,
/// gets a background wake: the app syncs and acknowledges delivery, so the
/// sender is not left at one tick. An alert that waited past
/// `STALE_ALERT_SECONDS` (retries, a backlog) is listed without sound, so a
/// burst of late alerts after an outage does not ring for each one.
fn send_presentation(queued: Presentation, muted: bool, alerts_off: bool, waited_seconds: i64) -> Presentation {
    if muted || alerts_off || queued == Presentation::Background {
        Presentation::Background
    } else if queued == Presentation::Alert && waited_seconds > STALE_ALERT_SECONDS {
        Presentation::Quiet
    } else {
        queued
    }
}

/// When retries of a push queued at `created` stop: its own expiry or the
/// retry window, whichever comes first.
fn retry_deadline(created: DateTime<Utc>, ttl_seconds: u32) -> DateTime<Utc> {
    created + chrono::Duration::seconds(i64::from(ttl_seconds).min(RETRY_WINDOW_SECONDS))
}

/// Capped exponential backoff, never below `minimum`, plus up to 50% jitter
/// so a burst of failures does not retry in lockstep. `jitter` is in [0, 1).
fn retry_delay_seconds(attempt: i32, minimum: i64, jitter: f64) -> i64 {
    let exponent = attempt.clamp(1, 20) as u32;
    let base = (1i64 << exponent).min(MAX_BACKOFF_SECONDS).max(minimum.max(0));
    base + (base as f64 * jitter.clamp(0.0, 1.0) / 2.0) as i64
}

#[derive(Debug, PartialEq, Eq)]
enum RetryPlan {
    After(i64),
    GiveUp(&'static str),
}

fn plan_retry(attempt: i32, minimum: i64, jitter: f64, now: DateTime<Utc>, deadline: DateTime<Utc>) -> RetryPlan {
    if attempt >= MAX_ATTEMPTS {
        return RetryPlan::GiveUp("max_attempts");
    }
    let delay = retry_delay_seconds(attempt, minimum, jitter);
    if now + chrono::Duration::seconds(delay) > deadline {
        return RetryPlan::GiveUp("deadline");
    }
    RetryPlan::After(delay)
}

/// Deletes finished rows older than the retention window in batches, so each
/// statement is short and the worker's inserts never wait long. Returns how
/// many rows were removed.
pub async fn purge_finished(db: &PgPool) -> Result<u64, sqlx::Error> {
    let mut total = 0;
    loop {
        let deleted = sqlx::query(
            "DELETE FROM push_outbox WHERE id IN (SELECT id FROM push_outbox \
             WHERE delivered_at IS NOT NULL AND delivered_at < now() - ($1 * interval '1 day') \
             ORDER BY delivered_at LIMIT $2)",
        )
        .bind(RETENTION_DAYS)
        .bind(PURGE_BATCH)
        .execute(db)
        .await?
        .rows_affected();
        total += deleted;
        if deleted < PURGE_BATCH as u64 {
            return Ok(total);
        }
        // Leave I/O room for live traffic between full batches.
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

/// Spawns the hourly purge of finished outbox rows (same pattern as the
/// direct-transfer signaling cleanup).
pub fn spawn_cleanup(db: PgPool) {
    tokio::spawn(async move {
        loop {
            let _ = sqlx::query("DELETE FROM session_check_wakes WHERE expires_at<=now() OR attempts>=10").execute(&db).await;
            match purge_finished(&db).await {
                Ok(0) => {}
                Ok(removed) => tracing::info!(removed, "purged finished push outbox rows"),
                Err(_) => tracing::warn!("push outbox cleanup will retry"),
            }
            tokio::time::sleep(Duration::from_secs(3600)).await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_grows_caps_and_respects_the_minimum() {
        assert_eq!(retry_delay_seconds(1, 0, 0.0), 2);
        assert_eq!(retry_delay_seconds(2, 0, 0.0), 4);
        assert_eq!(retry_delay_seconds(9, 0, 0.0), 512);
        assert_eq!(retry_delay_seconds(10, 0, 0.0), MAX_BACKOFF_SECONDS);
        assert_eq!(retry_delay_seconds(49, 0, 0.0), MAX_BACKOFF_SECONDS);
        assert_eq!(retry_delay_seconds(i32::MAX, 0, 0.0), MAX_BACKOFF_SECONDS);
        // Apple 5xx: 30 s minimum, jitter keeps it within 30-45 s.
        assert_eq!(retry_delay_seconds(1, 30, 0.0), 30);
        assert_eq!(retry_delay_seconds(1, 30, 0.999), 44);
        assert_eq!(retry_delay_seconds(1, 300, 0.0), 300);
        // Out-of-range jitter is clamped rather than trusted.
        assert_eq!(retry_delay_seconds(10, 0, 7.0), MAX_BACKOFF_SECONDS * 3 / 2);
        assert_eq!(retry_delay_seconds(1, -5, -1.0), 2);
    }

    #[test]
    fn retries_stop_at_the_deadline_or_the_backstop() {
        let now = Utc::now();
        let far = now + chrono::Duration::hours(1);
        assert_eq!(plan_retry(1, 0, 0.0, now, far), RetryPlan::After(2));
        assert_eq!(plan_retry(1, 30, 0.0, now, far), RetryPlan::After(30));
        assert_eq!(plan_retry(1, 30, 0.0, now, now + chrono::Duration::seconds(29)), RetryPlan::GiveUp("deadline"));
        assert_eq!(plan_retry(MAX_ATTEMPTS - 1, 0, 0.0, now, far), RetryPlan::After(MAX_BACKOFF_SECONDS));
        assert_eq!(plan_retry(MAX_ATTEMPTS, 0, 0.0, now, far), RetryPlan::GiveUp("max_attempts"));
    }

    #[test]
    fn deadline_is_the_sooner_of_expiry_and_the_retry_window() {
        let created = Utc::now();
        assert_eq!(retry_deadline(created, 86400), created + chrono::Duration::seconds(RETRY_WINDOW_SECONDS));
        assert_eq!(retry_deadline(created, 60), created + chrono::Duration::seconds(60));
        assert_eq!(retry_deadline(created, u32::MAX), created + chrono::Duration::seconds(RETRY_WINDOW_SECONDS));
    }

    #[test]
    fn delivery_only_makes_a_message_quieter() {
        use Presentation::{Alert, Background, Quiet};
        assert_eq!(send_presentation(Alert, false, false, 0), Alert);
        assert_eq!(send_presentation(Alert, false, false, STALE_ALERT_SECONDS), Alert);
        // Late alerts (retries after an outage) are listed, not rung.
        assert_eq!(send_presentation(Alert, false, false, STALE_ALERT_SECONDS + 1), Quiet);
        assert_eq!(send_presentation(Quiet, false, false, 0), Quiet);
        assert_eq!(send_presentation(Quiet, false, false, 86_400), Quiet);
        for queued in [Alert, Quiet, Background] {
            assert_eq!(send_presentation(queued, true, false, 0), Background, "muted");
            assert_eq!(send_presentation(queued, false, true, 0), Background, "alerts off");
        }
        assert_eq!(send_presentation(Background, false, false, 0), Background, "never louder");
    }

    #[test]
    fn only_the_alert_lane_orders_by_device_and_wakes_have_their_own_lane() {
        let alert = lane_filter(LANE_APNS_ALERT).unwrap();
        assert!(alert.contains("NOT EXISTS") && alert.contains("IS DISTINCT FROM 'background'"));
        assert!(lane_filter(LANE_APNS_BACKGROUND).unwrap().ends_with("='background'"));
        for lane in [LANE_VOIP, LANE_FCM_CALL, LANE_FCM_BULK, LANE_APNS_BACKGROUND] {
            assert!(!lane_filter(lane).unwrap().contains("NOT EXISTS"), "lane {lane} never waits behind an older row");
        }
        // Enum literals, not provider::text, so the pending index serves the claim.
        for lane in 0..LANES {
            assert!(!lane_filter(lane).unwrap().contains("::text"));
        }
        assert!(lane_filter(LANES).is_none());
    }

    #[test]
    fn a_failing_hour_stays_far_below_the_backstop() {
        // Worst case without jitter: count attempts that fit in the window.
        let (mut elapsed, mut attempt) = (0i64, 1i32);
        while elapsed + retry_delay_seconds(attempt, 0, 0.0) <= RETRY_WINDOW_SECONDS {
            elapsed += retry_delay_seconds(attempt, 0, 0.0);
            attempt += 1;
        }
        assert!(attempt < MAX_ATTEMPTS, "{attempt} attempts in one window");
    }
}

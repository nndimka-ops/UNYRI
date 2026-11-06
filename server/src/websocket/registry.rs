use std::collections::HashMap;
use std::collections::HashSet;
use std::sync::Arc;

use tokio::sync::{mpsc, RwLock};
use uuid::Uuid;

/// Tracks active WebSocket connections per *device* (not per user) so
/// message events - now per-device Signal Protocol envelopes - can be
/// pushed to exactly the connections that can actually decrypt them. A
/// device may have several simultaneous connections (multiple tabs/reconnect
/// overlap), so each device_id maps to a set of independently-addressable
/// connections.
pub struct ConnectionRegistry {
    inner: RwLock<HashMap<Uuid, HashMap<Uuid, ConnectionSender>>>,
    fresh: RwLock<HashMap<(Uuid, Uuid), std::time::Instant>>,
    presence: RwLock<HashMap<(Uuid, Uuid), ForegroundPresence>>,
    last_seen: RwLock<HashMap<Uuid, chrono::DateTime<chrono::Utc>>>,
    pending_bytes: Arc<tokio::sync::Semaphore>,
}
const CONNECTION_QUEUE_BYTES: usize=4*1024*1024;
const TOTAL_QUEUE_BYTES: usize=64*1024*1024;
impl Default for ConnectionRegistry {
    fn default() -> Self {Self {inner:Default::default(),fresh:Default::default(),presence:Default::default(),last_seen:Default::default(),pending_bytes:Arc::new(tokio::sync::Semaphore::new(TOTAL_QUEUE_BYTES))}}
}
#[derive(Debug)]
pub struct QueuedFrame {
    text: String,
    _global_bytes: tokio::sync::OwnedSemaphorePermit,
    _connection_bytes: tokio::sync::OwnedSemaphorePermit,
}
impl std::ops::Deref for QueuedFrame {type Target=str;fn deref(&self)->&str {&self.text}}
impl QueuedFrame {
    pub fn as_str(&self)->&str {&self.text}
    pub fn into_text(self)->String {self.text}
}
struct ConnectionSender {sender:mpsc::Sender<QueuedFrame>,bytes:Arc<tokio::sync::Semaphore>}
impl ConnectionSender {
    fn is_closed(&self)->bool {self.sender.is_closed()}
    fn enqueue(&self,payload:&str,global:&Arc<tokio::sync::Semaphore>)->bool {
        let Ok(size)=u32::try_from(payload.len().max(1)) else {return false;};
        let Ok(all)=global.clone().try_acquire_many_owned(size) else {return false;};
        let Ok(local)=self.bytes.clone().try_acquire_many_owned(size) else {return false;};
        self.sender.try_send(QueuedFrame {text:payload.to_owned(),_global_bytes:all,_connection_bytes:local}).is_ok()
    }
}

struct ForegroundPresence {
    user_id: Uuid,
    confirmed: std::time::Instant,
}

pub const FOREGROUND_TTL: std::time::Duration = std::time::Duration::from_secs(3);

impl ConnectionRegistry {
    /// Privacy-preserving live counters for the owner dashboard. Only
    /// aggregate counts leave the registry; device and user identifiers do not.
    pub async fn admin_metrics(&self) -> (usize, usize, usize) {
        let connections = self.inner.read().await;
        let fresh = self.fresh.read().await;
        let mut connected_devices = 0usize;
        let mut live_connections = 0usize;
        for (device, entries) in connections.iter() {
            let mut device_live = false;
            for (connection, sender) in entries {
                let live = !sender.is_closed() && fresh.get(&(*device, *connection))
                    .is_some_and(|at| at.elapsed() < std::time::Duration::from_secs(60));
                if live {
                    live_connections += 1;
                    device_live = true;
                }
            }
            if device_live { connected_devices += 1; }
        }
        drop(fresh);
        drop(connections);
        let foreground_users: HashSet<Uuid> = self.presence.read().await.values()
            .filter(|presence| presence.confirmed.elapsed() < FOREGROUND_TTL)
            .map(|presence| presence.user_id)
            .collect();
        (live_connections, connected_devices, foreground_users.len())
    }

    pub async fn foreground(&self, user_id: Uuid, device_id: Uuid, conn_id: Uuid, active: bool) {
        let key = (device_id, conn_id);
        if active {
            self.presence.write().await.insert(key, ForegroundPresence { user_id, confirmed: std::time::Instant::now() });
            self.last_seen.write().await.insert(user_id, chrono::Utc::now());
        } else {
            self.presence.write().await.remove(&key);
        }
    }

    pub async fn foreground_status(&self, user_id: Uuid) -> (bool, Option<chrono::DateTime<chrono::Utc>>) {
        let online = self.presence.read().await.values().any(|p| p.user_id == user_id && p.confirmed.elapsed() < FOREGROUND_TTL);
        let seen = self.last_seen.read().await.get(&user_id).copied();
        (online, seen)
    }

    pub async fn last_seen_snapshot(&self) -> HashMap<Uuid, chrono::DateTime<chrono::Utc>> {
        self.last_seen.read().await.clone()
    }

    pub async fn foreground_batch(&self, ids: &[Uuid]) -> HashMap<Uuid, (bool, Option<chrono::DateTime<chrono::Utc>>)> {
        let mut result: HashMap<_, _> = ids.iter().map(|id| (*id, (false, None))).collect();
        for p in self.presence.read().await.values() {
            if p.confirmed.elapsed() < FOREGROUND_TTL {
                if let Some(value) = result.get_mut(&p.user_id) { value.0 = true; }
            }
        }
        let seen = self.last_seen.read().await;
        for (id, value) in &mut result { value.1 = seen.get(id).copied(); }
        result
    }

    pub async fn forget_persisted_inactive(&self, user: Uuid, at: chrono::DateTime<chrono::Utc>) -> bool {
        let mut presence = self.presence.write().await;
        if presence.values().any(|p| p.user_id == user && p.confirmed.elapsed() < FOREGROUND_TTL) { return false; }
        let mut seen = self.last_seen.write().await;
        if seen.get(&user) != Some(&at) { return false; }
        seen.remove(&user);
        presence.retain(|_, p| p.user_id != user);
        true
    }
    /// Registers a new connection for `device_id`, returning its id (used to
    /// unregister later) and the receiving half the socket task should pull
    /// outgoing frames from.
    pub async fn register(&self, device_id: Uuid) -> (Uuid, mpsc::Receiver<QueuedFrame>) {
        let (tx, rx) = mpsc::channel(256);
        let conn_id = Uuid::new_v4();
        self.inner.write().await.entry(device_id).or_default().insert(conn_id, ConnectionSender {sender:tx,bytes:Arc::new(tokio::sync::Semaphore::new(CONNECTION_QUEUE_BYTES))});
        self.touch(device_id, conn_id).await;
        (conn_id, rx)
    }

    pub async fn touch(&self, device_id: Uuid, conn_id: Uuid) {
        self.fresh.write().await.insert((device_id, conn_id), std::time::Instant::now());
    }

    /// A live connection must also have received a frame recently. Half-open
    /// sockets cannot leave a contact permanently online after network loss.
    pub async fn online(&self, devices: &[Uuid]) -> bool {
        let connections = self.inner.read().await;
        let fresh = self.fresh.read().await;
        devices.iter().any(|device| connections.get(device).is_some_and(|entries|
            entries.iter().any(|(id, sender)| !sender.is_closed() && fresh.get(&(*device, *id))
                .is_some_and(|at| at.elapsed() < std::time::Duration::from_secs(60)))))
    }

    /// Volatile UI hints must not disconnect a slow client or displace durable events.
    pub async fn send_ephemeral(&self, device_id: Uuid, payload: &str) {
        let guard = self.inner.read().await;
        if let Some(connections) = guard.get(&device_id) {
            for sender in connections.values() { let _ = sender.enqueue(payload,&self.pending_bytes); }
        }
    }

    pub async fn unregister(&self, device_id: Uuid, conn_id: Uuid) {
        self.presence.write().await.remove(&(device_id, conn_id));
        let mut guard = self.inner.write().await;
        self.fresh.write().await.remove(&(device_id, conn_id));
        if let Some(connections) = guard.get_mut(&device_id) {
            connections.remove(&conn_id);
            if connections.is_empty() {
                guard.remove(&device_id);
            }
        }
    }

    /// Delivers a pre-serialized payload to every active connection of
    /// `device_id`. Silently drops if the device has no active connection -
    /// the caller doesn't need to (and shouldn't) treat that as an error:
    /// Postgres, not the socket, is the source of truth, so the recipient
    /// device will pick its envelope up on its next history sync regardless.
    pub async fn send_to_device(&self, device_id: Uuid, payload: &str) {
        let mut guard = self.inner.write().await;
        if let Some(connections) = guard.get_mut(&device_id) {
            // A stalled client must not retain an unbounded queue of ciphertext.
            // Dropping its sender closes the socket after the bounded queue drains;
            // the reconnect sync recovers any missed events from Postgres.
            connections.retain(|_, sender| sender.enqueue(payload,&self.pending_bytes));
            if connections.is_empty() { guard.remove(&device_id); }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn queued_bytes_remain_charged_after_sender_eviction_until_consumed_or_dropped() {
        let registry=ConnectionRegistry::default();let device=Uuid::new_v4();
        let (_,mut receiver)=registry.register(device).await;
        let payload="x".repeat(CONNECTION_QUEUE_BYTES);
        registry.send_to_device(device,&payload).await;
        assert_eq!(registry.pending_bytes.available_permits(),TOTAL_QUEUE_BYTES-CONNECTION_QUEUE_BYTES);
        registry.send_to_device(device,"overflow").await;
        assert!(registry.inner.read().await.is_empty());
        assert_eq!(registry.pending_bytes.available_permits(),TOTAL_QUEUE_BYTES-CONNECTION_QUEUE_BYTES);
        let frame=receiver.recv().await.unwrap();assert_eq!(frame.len(),CONNECTION_QUEUE_BYTES);
        drop(frame);assert_eq!(registry.pending_bytes.available_permits(),TOTAL_QUEUE_BYTES);
    }

    #[tokio::test]
    async fn foreground_presence_is_not_transport_liveness_and_aggregates_devices() {
        let registry = ConnectionRegistry::default();
        let user = Uuid::new_v4(); let a = Uuid::new_v4(); let b = Uuid::new_v4();
        let (ca, _ra) = registry.register(a).await;
        let (cb, _rb) = registry.register(b).await;
        assert!(!registry.foreground_status(user).await.0); // background socket
        registry.foreground(user, a, ca, true).await;
        assert!(registry.foreground_status(user).await.0);
        registry.foreground(user, b, cb, true).await;
        registry.foreground(user, a, ca, false).await;
        assert!(registry.foreground_status(user).await.0); // another visible phone
        registry.unregister(b, cb).await;
        let (online, seen) = registry.foreground_status(user).await;
        assert!(!online); assert!(seen.is_some());
    }

    #[tokio::test]
    async fn silent_foreground_connection_expires_without_erasing_last_visit() {
        let registry = ConnectionRegistry::default();
        let user = Uuid::new_v4(); let device = Uuid::new_v4();
        let (connection, _rx) = registry.register(device).await;
        registry.foreground(user, device, connection, true).await;
        let seen = registry.foreground_status(user).await.1;
        registry.presence.write().await.get_mut(&(device, connection)).unwrap().confirmed =
            std::time::Instant::now() - FOREGROUND_TTL - std::time::Duration::from_millis(1);
        assert_eq!(registry.foreground_status(user).await, (false, seen));
    }

    #[tokio::test]
    async fn online_requires_fresh_live_connection_and_handles_multiple_devices() {
        let registry = ConnectionRegistry::default();
        let a = Uuid::new_v4(); let b = Uuid::new_v4();
        assert!(!registry.online(&[a]).await);
        let (id, _rx) = registry.register(a).await;
        assert!(registry.online(&[a, b]).await);
        registry.fresh.write().await.insert((a, id), std::time::Instant::now() - std::time::Duration::from_secs(61));
        assert!(!registry.online(&[a]).await);
        registry.touch(a, id).await;
        let (second, _rx2) = registry.register(b).await;
        registry.unregister(a, id).await;
        assert!(registry.online(&[a, b]).await);
        registry.unregister(b, second).await;
        assert!(!registry.online(&[a, b]).await);
    }

    #[tokio::test]
    async fn ephemeral_overflow_does_not_disconnect_or_grow_the_queue() {
        let registry = ConnectionRegistry::default();
        let device = Uuid::new_v4();
        let (_, mut receiver) = registry.register(device).await;
        for _ in 0..300 { registry.send_ephemeral(device, "draft").await; }
        assert_eq!(receiver.len(), 256);
        assert!(!registry.inner.read().await.is_empty());
        for _ in 0..256 { receiver.recv().await.unwrap(); }
        registry.send_to_device(device, "message").await;
        assert_eq!(receiver.recv().await.as_deref(), Some("message"));
    }

    #[tokio::test]
    async fn slow_reader_is_disconnected_at_the_queue_bound() {
        let registry = ConnectionRegistry::default();
        let device = Uuid::new_v4();
        let (_, mut receiver) = registry.register(device).await;
        for _ in 0..257 { registry.send_to_device(device, "event").await; }
        let mut count = 0;
        while receiver.recv().await.is_some() { count += 1; }
        assert_eq!(count, 256);
        assert!(registry.inner.read().await.is_empty());
    }

    #[tokio::test]
    async fn admin_metrics_are_aggregate_and_ignore_stale_connections() {
        let registry = ConnectionRegistry::default();
        let user = Uuid::new_v4();
        let device = Uuid::new_v4();
        let (connection, _rx) = registry.register(device).await;
        registry.foreground(user, device, connection, true).await;
        assert_eq!(registry.admin_metrics().await, (1, 1, 1));
        registry.fresh.write().await.insert((device, connection),
            std::time::Instant::now() - std::time::Duration::from_secs(61));
        assert_eq!(registry.admin_metrics().await, (0, 0, 1));
    }
}

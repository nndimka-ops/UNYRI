//! Process/state resource admission. Reservations outlive routing/queue entries.
use crate::auth::middleware::AuthUser;
use crate::error::AppError;
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use uuid::Uuid;

#[derive(Default)]
pub struct RuntimeResources {
    pub sockets: Arc<SocketAdmission>,
    pub conference_closures: Arc<crate::conferences::ClosureQueue>,
    sfu_authorizations: std::sync::OnceLock<Arc<tokio::sync::Semaphore>>,
}
impl RuntimeResources {
    pub fn sfu_authorization(&self) -> Result<tokio::sync::OwnedSemaphorePermit, AppError> {
        self.sfu_authorizations
            .get_or_init(|| Arc::new(tokio::sync::Semaphore::new(32)))
            .clone()
            .try_acquire_owned()
            .map_err(|_| {
                AppError::Unavailable("Signaling authorization is busy. Retry shortly.".into())
            })
    }
}

pub const SOCKETS_PER_DEVICE: usize = 4;
pub const SOCKETS_PER_USER: usize = 8;
pub const SOCKETS_PER_IP: usize = 64;
pub const SOCKETS_TOTAL: usize = 128;
pub const MAX_SOCKET_MESSAGE_BYTES: usize = 256 * 1024;

#[derive(Default)]
pub struct SocketAdmission {
    inner: Mutex<Sockets>,
}
#[derive(Default)]
struct Sockets {
    live: HashMap<Uuid, (Uuid, Uuid, IpAddr)>,
    device_frames: HashMap<Uuid, Window>,
    user_frames: HashMap<Uuid, Window>,
}
struct Window {
    start: Instant,
    frames: usize,
    bytes: usize,
}
impl Window {
    fn new(now: Instant) -> Self {
        Self {
            start: now,
            frames: 0,
            bytes: 0,
        }
    }
    fn admit(&mut self, bytes: usize, frames: usize, max_bytes: usize, now: Instant) -> bool {
        if now.duration_since(self.start) >= Duration::from_secs(1) {
            *self = Self::new(now);
        }
        if self.frames >= frames || bytes > max_bytes.saturating_sub(self.bytes) {
            return false;
        }
        self.frames += 1;
        self.bytes += bytes;
        true
    }
}
pub struct SocketReservation {
    owner: Arc<SocketAdmission>,
    id: Uuid,
    user: Uuid,
    device: Uuid,
    frames: Window,
}
impl SocketAdmission {
    pub fn active_connections(&self) -> usize {
        self.inner
            .lock()
            .map(|state| state.live.len())
            .unwrap_or(usize::MAX)
    }
    pub fn reserve(
        self: &Arc<Self>,
        auth: &AuthUser,
        ip: IpAddr,
    ) -> Result<SocketReservation, AppError> {
        let mut state = self.inner.lock().map_err(|_| AppError::Internal)?;
        if state.live.len() >= SOCKETS_TOTAL
            || state
                .live
                .values()
                .filter(|(_, device, _)| *device == auth.device_id)
                .count()
                >= SOCKETS_PER_DEVICE
            || state
                .live
                .values()
                .filter(|(user, _, _)| *user == auth.user_id)
                .count()
                >= SOCKETS_PER_USER
            || state
                .live
                .values()
                .filter(|(_, _, address)| *address == ip)
                .count()
                >= SOCKETS_PER_IP
        {
            return Err(AppError::RateLimited);
        }
        let now = Instant::now();
        state
            .device_frames
            .retain(|_, v| now.duration_since(v.start) < Duration::from_secs(60));
        state
            .user_frames
            .retain(|_, v| now.duration_since(v.start) < Duration::from_secs(60));
        // Bound retained reconnect budgets as well as live connections.
        if (!state.device_frames.contains_key(&auth.device_id) && state.device_frames.len() >= 4096)
            || (!state.user_frames.contains_key(&auth.user_id) && state.user_frames.len() >= 4096)
        {
            return Err(AppError::RateLimited);
        }
        let id = Uuid::new_v4();
        state.live.insert(id, (auth.user_id, auth.device_id, ip));
        Ok(SocketReservation {
            owner: self.clone(),
            id,
            user: auth.user_id,
            device: auth.device_id,
            frames: Window::new(now),
        })
    }
}
impl SocketReservation {
    /// Called on every incoming frame before parsing, logging or database work.
    pub fn admit_frame(&mut self, bytes: usize) -> bool {
        if bytes > MAX_SOCKET_MESSAGE_BYTES {
            return false;
        }
        let now = Instant::now();
        if !self.frames.admit(bytes, 80, 2 * 1024 * 1024, now) {
            return false;
        }
        let Ok(mut state) = self.owner.inner.lock() else {
            return false;
        };
        if !state
            .device_frames
            .entry(self.device)
            .or_insert_with(|| Window::new(now))
            .admit(bytes, 80, 2 * 1024 * 1024, now)
        {
            return false;
        }
        state
            .user_frames
            .entry(self.user)
            .or_insert_with(|| Window::new(now))
            .admit(bytes, 120, 4 * 1024 * 1024, now)
    }
}
impl Drop for SocketReservation {
    fn drop(&mut self) {
        if let Ok(mut state) = self.owner.inner.lock() {
            state.live.remove(&self.id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn auth(user: Uuid, device: Uuid) -> AuthUser {
        AuthUser {
            user_id: user,
            device_id: device,
            session_id: Uuid::new_v4(),
        }
    }
    #[test]
    fn reservations_allow_overlap_and_release_failed_upgrades() {
        let gate = Arc::new(SocketAdmission::default());
        let user = Uuid::new_v4();
        let device = Uuid::new_v4();
        let ip = "127.0.0.1".parse().unwrap();
        let mut leases = vec![];
        for _ in 0..SOCKETS_PER_DEVICE {
            leases.push(gate.reserve(&auth(user, device), ip).unwrap());
        }
        assert!(matches!(
            gate.reserve(&auth(user, device), ip),
            Err(AppError::RateLimited)
        ));
        drop(leases.pop());
        assert!(gate.reserve(&auth(user, device), ip).is_ok());
        assert_eq!(
            gate.inner.lock().unwrap().live.len(),
            SOCKETS_PER_DEVICE - 1
        );
    }
    #[test]
    fn user_ip_and_global_caps_are_separate_and_atomic() {
        let gate = Arc::new(SocketAdmission::default());
        let user = Uuid::new_v4();
        let ip = "127.0.0.1".parse().unwrap();
        let mut leases = vec![];
        for _ in 0..SOCKETS_PER_USER {
            leases.push(gate.reserve(&auth(user, Uuid::new_v4()), ip).unwrap());
        }
        assert!(gate.reserve(&auth(user, Uuid::new_v4()), ip).is_err());
        for _ in SOCKETS_PER_USER..SOCKETS_PER_IP {
            leases.push(
                gate.reserve(&auth(Uuid::new_v4(), Uuid::new_v4()), ip)
                    .unwrap(),
            );
        }
        assert!(gate
            .reserve(&auth(Uuid::new_v4(), Uuid::new_v4()), ip)
            .is_err());
        let other = "127.0.0.2".parse().unwrap();
        for _ in SOCKETS_PER_IP..SOCKETS_TOTAL {
            leases.push(
                gate.reserve(&auth(Uuid::new_v4(), Uuid::new_v4()), other)
                    .unwrap(),
            );
        }
        assert!(gate
            .reserve(
                &auth(Uuid::new_v4(), Uuid::new_v4()),
                "127.0.0.3".parse().unwrap()
            )
            .is_err());
        drop(leases);
        assert!(gate.inner.lock().unwrap().live.is_empty());
    }
    #[test]
    fn frame_budget_survives_reconnect_and_allows_ice_and_maximum_drafts() {
        let gate = Arc::new(SocketAdmission::default());
        let a = auth(Uuid::new_v4(), Uuid::new_v4());
        let ip = "127.0.0.1".parse().unwrap();
        let mut first = gate.reserve(&a, ip).unwrap();
        for _ in 0..50 {
            assert!(first.admit_frame(500));
        } // normal ICE burst
        assert!(first.admit_frame(160 * 1024)); // 16-envelope draft
        drop(first);
        let mut second = gate.reserve(&a, ip).unwrap();
        for _ in 51..80 {
            assert!(second.admit_frame(1));
        }
        assert!(!second.admit_frame(1));
        assert!(!second.admit_frame(MAX_SOCKET_MESSAGE_BYTES + 1));
    }
    #[test]
    fn simultaneous_upgrades_cannot_overbook_one_device() {
        let gate = Arc::new(SocketAdmission::default());
        let barrier = Arc::new(std::sync::Barrier::new(16));
        let user = Uuid::new_v4();
        let device = Uuid::new_v4();
        let mut threads = vec![];
        for _ in 0..16 {
            let gate = gate.clone();
            let barrier = barrier.clone();
            threads.push(std::thread::spawn(move || {
                barrier.wait();
                gate.reserve(&auth(user, device), "127.0.0.1".parse().unwrap())
            }));
        }
        let results: Vec<_> = threads.into_iter().map(|t| t.join().unwrap()).collect();
        assert_eq!(
            results.iter().filter(|r| r.is_ok()).count(),
            SOCKETS_PER_DEVICE
        );
        drop(results);
        assert!(gate.inner.lock().unwrap().live.is_empty());
    }
    #[tokio::test]
    async fn outgoing_queue_eviction_does_not_release_a_live_socket_reservation() {
        let gate = Arc::new(SocketAdmission::default());
        let a = auth(Uuid::new_v4(), Uuid::new_v4());
        let ip = "127.0.0.1".parse().unwrap();
        let registry = crate::websocket::registry::ConnectionRegistry::default();
        let mut leases = vec![];
        let mut receivers = vec![];
        for _ in 0..SOCKETS_PER_DEVICE {
            leases.push(gate.reserve(&a, ip).unwrap());
            receivers.push(registry.register(a.device_id).await.1);
        }
        for _ in 0..257 {
            registry.send_to_device(a.device_id, "fixture").await;
        }
        assert_eq!(registry.admin_metrics().await.0, 0);
        assert!(gate.reserve(&a, ip).is_err());
        drop(leases);
        assert!(gate.reserve(&a, ip).is_ok());
        drop(receivers);
    }
}

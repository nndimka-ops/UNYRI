use super::*;
use std::collections::HashSet;
use std::sync::{Arc, Mutex};

#[derive(Default)]
pub struct ClosureQueue {
    pending: Mutex<Pending>,
    completed: tokio::sync::Notify,
}
#[derive(Default)]
struct Pending {
    requested: u64,
    completed: u64,
    running: bool,
    all: bool,
    users: HashSet<Uuid>,
}
impl Pending {
    fn enqueue(&mut self, users: Option<&[Uuid]>) -> (u64, bool) {
        self.requested += 1;
        if let Some(users) = users {
            if !self.all {
                self.users.extend(users);
            }
            if self.users.len() > 256 {
                self.users.clear();
                self.all = true;
            }
        } else {
            self.all = true;
            self.users.clear();
        }
        let start = !self.running;
        self.running = true;
        (self.requested, start)
    }
    fn take(&mut self) -> Option<(u64, bool, Vec<Uuid>)> {
        if self.completed == self.requested {
            self.running = false;
            return None;
        }
        let all = self.all;
        self.all = false;
        Some((self.requested, all, self.users.drain().collect()))
    }
}
impl ClosureQueue {
    pub async fn request(self: &Arc<Self>, state: &AppState, users: Option<&[Uuid]>) {
        let (generation, start) = self
            .pending
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .enqueue(users);
        if start {
            let queue = self.clone();
            let state = state.clone();
            tokio::spawn(async move {
                loop {
                    let work = queue
                        .pending
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .take();
                    let Some((generation, all, users)) = work else {
                        break;
                    };
                    if reconcile_security(&state, all, &users).await.is_err() {
                        tracing::warn!("conference authorization reconciliation pending");
                    }
                    queue
                        .pending
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .completed = generation;
                    queue.completed.notify_waiters();
                    // A mutation after our SQL snapshot remains pending and
                    // receives its own pass; try_lock-and-return would lose it.
                }
            });
        }
        loop {
            let notified = self.completed.notified();
            if self
                .pending
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .completed
                >= generation
            {
                break;
            }
            notified.await;
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn concurrent_and_post_snapshot_security_mutations_are_not_lost() {
        let mut pending = Pending::default();
        let a = Uuid::new_v4();
        let b = Uuid::new_v4();
        let (first, start) = pending.enqueue(Some(&[a]));
        assert!(start);
        assert!(!pending.enqueue(Some(&[a])).1);
        let (snapshot, all, users) = pending.take().unwrap();
        assert!(!all);
        assert_eq!(users, vec![a]);
        let (late, start) = pending.enqueue(Some(&[b]));
        assert!(!start);
        pending.completed = snapshot;
        assert!(pending.completed >= first && pending.completed < late);
        let (next, all, users) = pending.take().unwrap();
        assert_eq!(next, late);
        assert!(!all);
        assert_eq!(users, vec![b]);
        pending.completed = next;
        assert!(pending.take().is_none());
        assert!(!pending.running);
    }
}

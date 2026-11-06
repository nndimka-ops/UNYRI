use std::sync::{Arc, OnceLock};

use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};

use crate::error::AppError;

pub(crate) fn work_gate() -> Arc<tokio::sync::Semaphore> {
    static GATE: OnceLock<Arc<tokio::sync::Semaphore>> = OnceLock::new();
    GATE.get_or_init(|| Arc::new(tokio::sync::Semaphore::new(2))).clone()
}

/// Bound CPU and Argon2 memory before entering Tokio's blocking queue. The
/// permit belongs to the computation, including after request cancellation.
pub async fn run<T, F>(work: F) -> Result<T, AppError>
where T: Send + 'static, F: FnOnce() -> Result<T, AppError> + Send + 'static {
    run_with_gate(work_gate(), work).await
}

async fn run_with_gate<T, F>(gate: Arc<tokio::sync::Semaphore>, work: F) -> Result<T, AppError>
where T: Send + 'static, F: FnOnce() -> Result<T, AppError> + Send + 'static {
    let permit = gate.try_acquire_owned().map_err(|_| AppError::Unavailable("Password verification is busy. Try again shortly.".into()))?;
    tokio::task::spawn_blocking(move || { let _permit = permit; work() })
        .await.map_err(|_| AppError::Internal)?
}

pub fn hash_password(password: &str) -> Result<String, AppError> {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|hash| hash.to_string())
        .map_err(|err| {
            tracing::error!(error = %err, "failed to hash password");
            AppError::Internal
        })
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    let Ok(parsed) = PasswordHash::new(hash) else {
        return false;
    };
    Argon2::default().verify_password(password.as_bytes(), &parsed).is_ok()
}

static DUMMY_HASH: OnceLock<String> = OnceLock::new();

/// A real Argon2id hash of a fixed, non-secret placeholder value. Verifying
/// against it when a username doesn't exist keeps login response timing
/// indistinguishable from a genuine wrong-password attempt, so it can't be
/// used to enumerate accounts.
pub fn dummy_hash() -> &'static str {
    DUMMY_HASH.get_or_init(|| hash_password("dummy-password-for-timing-safety").expect("dummy hash computation cannot fail"))
}

#[cfg(test)]
mod work_bound_tests {
    use super::*;
    #[tokio::test]
    async fn cancelled_request_keeps_the_cpu_permit_until_work_finishes() {
        let gate = Arc::new(tokio::sync::Semaphore::new(1));
        let (started, wait_started) = tokio::sync::oneshot::channel();
        let (finish, wait_finish) = std::sync::mpsc::channel();
        let first_gate=gate.clone();
        let task=tokio::spawn(async move { run_with_gate(first_gate, move || {
            let _=started.send(());
            wait_finish.recv_timeout(std::time::Duration::from_secs(3)).unwrap();
            Ok(())
        }).await });
        wait_started.await.unwrap();
        task.abort();
        assert!(matches!(run_with_gate(gate.clone(), || Ok(())).await, Err(AppError::Unavailable(_))));
        finish.send(()).unwrap();
        let permit=tokio::time::timeout(std::time::Duration::from_secs(2),gate.clone().acquire_owned()).await.unwrap().unwrap();
        drop(permit);
        assert!(run_with_gate(gate,|| Ok(())).await.is_ok());
    }
}

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};

use crate::error::AppError;

/// A deliberately small embedded denylist. Login still accepts existing passwords.
pub fn validate_new_password(password: &str) -> Result<(), AppError> {
    validate_new_password_localized(password, "en")
}

pub fn validate_new_password_localized(password: &str, language: &str) -> Result<(), AppError> {
    if !(8..=256).contains(&password.chars().count()) {
        return Err(AppError::Validation("Password must be 8-256 characters.".into()));
    }
    let normalized = password.trim().to_lowercase();
    if include_str!("common_passwords.txt").lines().any(|entry| entry == normalized) {
        let language = language.split([',', ';', '-']).next().unwrap_or("en").trim().to_ascii_lowercase();
        let message = match language.as_str() {
            "ru" => "Этот пароль слишком распространён. Выберите другой пароль.",
            "uk" => "Цей пароль надто поширений. Виберіть інший пароль.",
            "ro" => "Această parolă este prea comună. Alegeți o altă parolă.",
            "de" => "Dieses Passwort ist zu häufig. Wählen Sie ein anderes Passwort.",
            "fr" => "Ce mot de passe est trop courant. Choisissez un autre mot de passe.",
            "es" => "Esta contraseña es demasiado común. Elige otra contraseña.",
            "it" => "Questa password è troppo comune. Scegli una password diversa.",
            "pt" => "Esta senha é muito comum. Escolha outra senha.",
            "pl" => "To hasło jest zbyt popularne. Wybierz inne hasło.",
            "nl" => "Dit wachtwoord is te algemeen. Kies een ander wachtwoord.",
            "tr" => "Bu parola çok yaygın. Başka bir parola seçin.",
            "cs" => "Toto heslo je příliš běžné. Zvolte jiné heslo.",
            "hu" => "Ez a jelszó túl gyakori. Válasszon másik jelszót.",
            "bg" => "Тази парола е твърде често срещана. Изберете друга парола.",
            "el" => "Αυτός ο κωδικός είναι πολύ συνηθισμένος. Επιλέξτε άλλον κωδικό.",
            "ar" => "كلمة المرور هذه شائعة جداً. اختر كلمة مرور مختلفة.",
            "ja" => "このパスワードはよく使われています。別のパスワードを選んでください。",
            "ko" => "너무 흔한 비밀번호입니다. 다른 비밀번호를 선택하세요.",
            "zh" => "此密码过于常见。请选择其他密码。",
            _ => "This password is too common. Choose a different password.",
        };
        return Err(AppError::CommonPassword(message));
    }
    Ok(())
}

pub(crate) fn work_gate() -> Arc<tokio::sync::Semaphore> {
    static GATE: OnceLock<Arc<tokio::sync::Semaphore>> = OnceLock::new();
    GATE.get_or_init(|| Arc::new(tokio::sync::Semaphore::new(work_gate_permits()))).clone()
}

/// Size of [`work_gate`] on this host.
pub(crate) fn work_gate_permits() -> usize {
    gate_permits(available_cores())
}

fn available_cores() -> usize {
    std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2)
}

/// One concurrent Argon2 computation per core, at least two. The upper
/// bound keeps peak Argon2 memory bounded (about 19 MiB per computation with
/// the default parameters) on very large hosts.
fn gate_permits(cores: usize) -> usize {
    cores.clamp(2, 16)
}

/// Bound CPU and Argon2 memory before entering Tokio's blocking queue. The
/// permit belongs to the computation, including after request cancellation.
pub async fn run<T, F>(work: F) -> Result<T, AppError>
where T: Send + 'static, F: FnOnce() -> Result<T, AppError> + Send + 'static {
    run_with_gate(work_gate(), GATE_WAIT, work).await
}

/// A burst of hashing waits briefly for a permit instead of failing at once.
const GATE_WAIT: std::time::Duration = std::time::Duration::from_secs(3);

async fn run_with_gate<T, F>(gate: Arc<tokio::sync::Semaphore>, wait: std::time::Duration, work: F) -> Result<T, AppError>
where T: Send + 'static, F: FnOnce() -> Result<T, AppError> + Send + 'static {
    let permit = tokio::time::timeout(wait, gate.acquire_owned()).await
        .map_err(|_| AppError::Unavailable("Password verification is busy. Try again shortly.".into()))?
        .map_err(|_| AppError::Internal)?;
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
    let started = Instant::now();
    let matches = Argon2::default().verify_password(password.as_bytes(), &parsed).is_ok();
    record_verification_cost(started.elapsed());
    matches
}

/// Moving average of recent Argon2 verification times, in microseconds.
static VERIFY_COST_MICROS: AtomicU64 = AtomicU64::new(0);
/// Used until the first verification has been measured.
const DEFAULT_VERIFY_COST: Duration = Duration::from_millis(60);

fn record_verification_cost(elapsed: Duration) {
    let sample = u64::try_from(elapsed.as_micros()).unwrap_or(u64::MAX).max(1);
    let _ = VERIFY_COST_MICROS.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |old| {
        Some(if old == 0 { sample } else { (old.saturating_mul(7).saturating_add(sample)) / 8 })
    });
}

/// Wait about as long as one Argon2 verification without using a CPU permit.
///
/// Used when the global budget for sign-in attempts against non-existent
/// accounts is spent: answering such a request instantly would reveal that
/// the account does not exist, while hashing would let made-up usernames
/// take Argon2 capacity from real sign-ins. The wait does not include any
/// permit queueing that real verifications see under load.
pub async fn emulate_verification() {
    let measured = VERIFY_COST_MICROS.load(Ordering::Relaxed);
    let base = if measured == 0 { DEFAULT_VERIFY_COST } else { Duration::from_micros(measured) };
    let jitter = {
        use rand::Rng;
        rand::thread_rng().gen_range(0.85..1.15)
    };
    tokio::time::sleep(base.mul_f64(jitter)).await;
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

    #[test]
    fn new_password_policy_rejects_common_variants_but_keeps_existing_login() {
        for value in ["password", " PASSWORD123 ", "Qwerty123", "1234567890", "пароль123"] {
            assert!(matches!(validate_new_password(value), Err(AppError::CommonPassword(_))));
        }
        assert!(validate_new_password("distinct lengthy passphrase 47").is_ok());
        assert!(validate_new_password("short").is_err());
        assert!(validate_new_password(&"x".repeat(257)).is_err());
        let existing = hash_password("password").unwrap();
        assert!(verify_password("password", &existing));
        for (language, prefix) in [("ru-RU, en;q=0.9", "Этот"), ("uk", "Цей"), ("en", "This")] {
            match validate_new_password_localized("password", language) {
                Err(AppError::CommonPassword(message)) => assert!(message.starts_with(prefix)),
                _ => panic!("expected localized common-password error"),
            }
        }
    }

    #[test]
    fn gate_scales_with_cores_within_bounds() {
        assert_eq!(gate_permits(1), 2);
        assert_eq!(gate_permits(2), 2);
        assert_eq!(gate_permits(8), 8);
        assert_eq!(gate_permits(128), 16);
    }

    #[tokio::test]
    async fn emulated_verification_waits_about_one_verification() {
        record_verification_cost(Duration::from_millis(40));
        let started = Instant::now();
        emulate_verification().await;
        assert!(started.elapsed() >= Duration::from_millis(5));
    }
    #[tokio::test]
    async fn cancelled_request_keeps_the_cpu_permit_until_work_finishes() {
        let gate = Arc::new(tokio::sync::Semaphore::new(1));
        let (started, wait_started) = tokio::sync::oneshot::channel();
        let (finish, wait_finish) = std::sync::mpsc::channel();
        let first_gate=gate.clone();
        let task=tokio::spawn(async move { run_with_gate(first_gate, std::time::Duration::from_millis(50), move || {
            let _=started.send(());
            wait_finish.recv_timeout(std::time::Duration::from_secs(3)).unwrap();
            Ok(())
        }).await });
        wait_started.await.unwrap();
        task.abort();
        assert!(matches!(run_with_gate(gate.clone(), std::time::Duration::from_millis(50), || Ok(())).await, Err(AppError::Unavailable(_))));
        finish.send(()).unwrap();
        let permit=tokio::time::timeout(std::time::Duration::from_secs(2),gate.clone().acquire_owned()).await.unwrap().unwrap();
        drop(permit);
        assert!(run_with_gate(gate, std::time::Duration::from_millis(50), || Ok(())).await.is_ok());
    }
}

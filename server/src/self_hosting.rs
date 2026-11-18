//! Local bootstrap and server registration policy. No plaintext codes in SQL,
//! API lists or audit records. Filesystem access is confined to operator paths.
use axum::{
    extract::{Path, Query, State},
    Json,
};
use chrono::{Duration, Utc};
use rand::RngCore;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use sqlx::{PgPool, Postgres, Transaction};
use std::{
    io::{Read, Write},
    path::{Path as FsPath, PathBuf},
};
use subtle::ConstantTimeEq;
use uuid::Uuid;

use crate::{
    admin::Admin,
    auth::{rate_limit, AuthUser},
    error::AppError,
    net::ClientIp,
    state::AppState,
};

const ALPHABET: &[u8; 32] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/// 80 random bits, grouped for transcription; case and separators are ignored.
pub fn generate_code() -> String {
    let mut bytes = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    bytes
        .chunks(4)
        .map(|group| {
            group
                .iter()
                .map(|b| ALPHABET[(b & 31) as usize] as char)
                .collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("-")
}

fn code_hash(code: &str) -> [u8; 32] {
    let normalized: String = code
        .chars()
        .filter(|c| !c.is_ascii_whitespace() && *c != '-')
        .map(|c| c.to_ascii_uppercase())
        .collect();
    Sha256::digest(normalized.as_bytes()).into()
}

fn matches_code(supplied: &str, stored: &[u8]) -> bool {
    // Always compare fixed-size digests, including unknown selectors.
    bool::from(code_hash(supplied).as_slice().ct_eq(stored))
}

pub fn data_dir() -> PathBuf {
    std::env::var_os("COCO_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("data"))
}
pub fn setup_code_path() -> PathBuf {
    std::env::var_os("COCO_SETUP_CODE_FILE")
        .map(PathBuf::from)
        .unwrap_or_else(|| data_dir().join("owner-setup-code"))
}

fn write_private_code(path: &FsPath, code: &str) -> anyhow::Result<()> {
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(|| FsPath::new("."));
    let mut dir = std::fs::DirBuilder::new();
    dir.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        dir.mode(0o700);
    }
    dir.create(parent)?;
    let temp = parent.join(format!(".owner-setup-{}", Uuid::new_v4()));
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| -> anyhow::Result<()> {
        let mut file = options.open(&temp)?;
        writeln!(file, "{code}")?;
        file.sync_all()?;
        // Windows rename cannot replace a file. Only remove this exact operator
        // path; no directory traversal or recursive deletion is performed.
        match std::fs::remove_file(path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
        std::fs::rename(&temp, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&temp);
    }
    result
}

pub fn remove_setup_file(path: &FsPath) {
    if let Err(error) = std::fs::remove_file(path) {
        if error.kind() != std::io::ErrorKind::NotFound {
            tracing::warn!(
                "Retired owner setup file could not be removed; its code is no longer valid"
            );
        }
    }
}

/// Called once after migrations, before serving. Keep a valid code across
/// restarts without logging it again. Lost/inconsistent files rotate the code.
pub async fn initialize(db: &PgPool, path: &FsPath) -> anyhow::Result<()> {
    let mut tx = db.begin().await?;
    sqlx::query("SELECT pg_advisory_xact_lock(2392001)")
        .execute(&mut *tx)
        .await?;
    let (completed, stored): (bool, Option<Vec<u8>>) =
        sqlx::query_as("SELECT completed,code_hash FROM server_bootstrap WHERE id=true FOR UPDATE")
            .fetch_one(&mut *tx)
            .await?;
    if completed {
        remove_setup_file(path);
        tx.commit().await?;
        return Ok(());
    }
    let existing = (|| -> std::io::Result<String> {
        let metadata = std::fs::symlink_metadata(path)?;
        if !metadata.is_file() || metadata.len() > 64 {
            return Err(std::io::ErrorKind::InvalidData.into());
        }
        let mut code = String::new();
        std::fs::File::open(path)?
            .take(65)
            .read_to_string(&mut code)?;
        Ok(code)
    })()
    .ok();
    if stored
        .as_ref()
        .zip(existing.as_ref())
        .is_some_and(|(hash, code)| matches_code(code, hash))
    {
        tx.commit().await?;
        return Ok(());
    }
    let code = generate_code();
    write_private_code(path, &code)?;
    sqlx::query("UPDATE server_bootstrap SET code_hash=$1 WHERE id=true")
        .bind(code_hash(&code).as_slice())
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    // Explicit bootstrap credential delivery, exactly once per generated code.
    tracing::warn!("FIRST OWNER SETUP CODE: {code} — enter in your signed-in app to claim this server. Keep private.");
    Ok(())
}

/// A server name chosen at install time: 1-64 characters after trimming, no
/// control characters, and within the console's 200-byte limit.
pub fn valid_server_name(raw: &str) -> Option<String> {
    let name = raw.trim();
    let chars = name.chars().count();
    if chars == 0 || chars > 64 || name.len() > 200 || name.chars().any(char::is_control) {
        return None;
    }
    Some(name.to_owned())
}

/// Applies the installer's `UNYRI_SERVER_NAME` only while the server is still
/// unclaimed (first-owner setup not completed). After that the owner's console
/// setting is authoritative and the variable is ignored. Returns whether the
/// stored name changed. An invalid value is ignored with a warning; startup
/// never fails because of it.
pub async fn seed_server_name(db: &PgPool, requested: Option<&str>) -> anyhow::Result<bool> {
    let Some(raw) = requested else {
        return Ok(false);
    };
    let Some(name) = valid_server_name(raw) else {
        tracing::warn!("UNYRI_SERVER_NAME ignored: use 1-64 printable characters");
        return Ok(false);
    };
    let updated = sqlx::query(
        "UPDATE server_policy SET name=$1 WHERE id=true AND name<>$1 \
         AND NOT (SELECT completed FROM server_bootstrap WHERE id=true) \
         AND NOT EXISTS(SELECT 1 FROM server_administrators WHERE role='owner')",
    )
    .bind(&name)
    .execute(db)
    .await?;
    Ok(updated.rows_affected() == 1)
}

async fn available(db: &PgPool) -> Result<bool, AppError> {
    Ok(sqlx::query_scalar("SELECT NOT completed AND code_hash IS NOT NULL AND NOT EXISTS(SELECT 1 FROM server_administrators WHERE role='owner') FROM server_bootstrap WHERE id=true")
        .fetch_one(db).await?)
}

pub async fn setup_state(
    State(state): State<AppState>,
    _auth: AuthUser,
) -> Result<Json<Value>, AppError> {
    Ok(Json(json!({"available":available(&state.db).await?})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Claim {
    code: String,
}

pub async fn claim_owner(
    State(state): State<AppState>,
    auth: AuthUser,
    ClientIp(ip): ClientIp,
    body: Result<Json<Claim>, axum::extract::rejection::JsonRejection>,
) -> Result<Json<Value>, AppError> {
    // Availability is checked before rate limiting so a retired endpoint always
    // has the same response, even for previously throttled accounts/networks.
    if !available(&state.db).await? {
        return Err(AppError::SetupNotAvailable);
    }
    rate_limit::enforce_auth(&state, "owner-claim", ip, 5, 900).await?;
    if !rate_limit::try_enforce_key(
        &state,
        "owner-claim-account",
        &auth.user_id.to_string(),
        5,
        900,
    )
    .await?
    {
        return Err(AppError::RateLimited);
    }
    let Json(body) = body.map_err(|_| AppError::Validation("A setup code is required.".into()))?;
    let mut tx = state.db.begin().await?;
    crate::auth::admission::lock(&mut tx, false).await?;
    sqlx::query("SELECT pg_advisory_xact_lock(2392001)")
        .execute(&mut *tx)
        .await?;
    crate::auth::middleware::lock_active_session(&mut tx, &auth).await?;
    let (completed, hash): (bool, Option<Vec<u8>>) =
        sqlx::query_as("SELECT completed,code_hash FROM server_bootstrap WHERE id=true FOR UPDATE")
            .fetch_one(&mut *tx)
            .await?;
    if completed || hash.is_none() {
        return Err(AppError::SetupNotAvailable);
    }
    if body.code.len() > 64 || !matches_code(&body.code, hash.as_deref().unwrap_or(&[0; 32])) {
        sqlx::query("INSERT INTO admin_audit(actor,action) VALUES($1,'setup:claim-invalid')")
            .bind(auth.user_id)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        return Err(AppError::SetupInvalid);
    }
    // Match coco-admin grant-owner, including invalidation of prior admin auth.
    sqlx::query("INSERT INTO server_administrators(user_id,role) VALUES($1,'owner') ON CONFLICT(user_id) DO UPDATE SET role='owner'")
        .bind(auth.user_id).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM admin_sessions WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM admin_key_challenges WHERE user_id=$1")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO admin_audit(actor,action,target) VALUES($1,'setup:claim-owner',$1)")
        .bind(auth.user_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    remove_setup_file(&setup_code_path());
    Ok(Json(json!({"ok":true,"role":"owner"})))
}

fn manager(admin: &Admin) -> Result<(), AppError> {
    if !matches!(admin.role.as_str(), "owner" | "admin") {
        return Err(AppError::NotFound("Not found.".into()));
    }
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RegistrationMode {
    Open,
    InviteOnly,
}
impl RegistrationMode {
    fn as_str(&self) -> &'static str {
        match self {
            Self::Open => "open",
            Self::InviteOnly => "invite_only",
        }
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RegistrationPolicy {
    registration_mode: RegistrationMode,
}

pub async fn registration_policy(
    State(state): State<AppState>,
    admin: Admin,
) -> Result<Json<Value>, AppError> {
    manager(&admin)?;
    let (mode, open): (String, bool) = sqlx::query_as(
        "SELECT registration_mode,registration_open FROM server_policy WHERE id=true",
    )
    .fetch_one(&state.db)
    .await?;
    Ok(Json(
        json!({"registration_mode":mode,"registration_open":open}),
    ))
}
pub async fn save_registration(
    State(state): State<AppState>,
    admin: Admin,
    Json(body): Json<RegistrationPolicy>,
) -> Result<Json<Value>, AppError> {
    manager(&admin)?;
    let mut tx = state.db.begin().await?;
    sqlx::query("UPDATE server_policy SET registration_mode=$1 WHERE id=true")
        .bind(body.registration_mode.as_str())
        .execute(&mut *tx)
        .await?;
    sqlx::query("INSERT INTO admin_audit(actor,action,detail) VALUES($1,'registration:mode',jsonb_build_object('registration_mode',$2::text))")
        .bind(admin.user).bind(body.registration_mode.as_str()).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NewInvite {
    max_uses: Option<i32>,
    expires_in_seconds: Option<i64>,
}
impl NewInvite {
    fn limits(&self) -> Result<(i32, i64), AppError> {
        let uses = self.max_uses.unwrap_or(1);
        let seconds = self.expires_in_seconds.unwrap_or(7 * 86400);
        if !(1..=10000).contains(&uses) || !(60..=365 * 86400).contains(&seconds) {
            return Err(AppError::Validation(
                "Invite uses must be 1-10000 and lifetime 60-31536000 seconds.".into(),
            ));
        }
        Ok((uses, seconds))
    }
}

pub async fn create_invite(
    State(state): State<AppState>,
    admin: Admin,
    Json(body): Json<NewInvite>,
) -> Result<Json<Value>, AppError> {
    let (max_uses, seconds) = body.limits()?;
    if !rate_limit::try_enforce_key(&state, "invite-create", &admin.user.to_string(), 30, 3600)
        .await?
    {
        return Err(AppError::RateLimited);
    }
    let id = Uuid::new_v4();
    let secret = generate_code();
    let code = format!("{id}.{secret}");
    let expires = Utc::now() + Duration::seconds(seconds);
    let mut tx = state.db.begin().await?;
    sqlx::query("INSERT INTO registration_invites(id,code_hash,created_by,expires_at,max_uses) VALUES($1,$2,$3,$4,$5)")
        .bind(id).bind(code_hash(&secret).as_slice()).bind(admin.user).bind(expires).bind(max_uses).execute(&mut *tx).await?;
    sqlx::query("INSERT INTO admin_audit(actor,action,detail) VALUES($1,'invite:create',jsonb_build_object('invite_id',$2::uuid))")
        .bind(admin.user).bind(id).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(
        json!({"id":id,"code":code,"expires_at":expires,"max_uses":max_uses,"uses":0}),
    ))
}

#[derive(Deserialize)]
pub struct InvitePage {
    before: Option<Uuid>,
}
pub async fn list_invites(
    State(state): State<AppState>,
    _admin: Admin,
    Query(page): Query<InvitePage>,
) -> Result<Json<Value>, AppError> {
    let rows: Vec<Value> = sqlx::query_scalar("SELECT jsonb_build_object('id',i.id,'created_by',i.created_by,'creator',u.display_name,'created_at',i.created_at,'expires_at',i.expires_at,'max_uses',i.max_uses,'uses',i.uses,'revoked_at',i.revoked_at,'status',CASE WHEN i.revoked_at IS NOT NULL THEN 'revoked' WHEN i.expires_at<=now() THEN 'expired' WHEN i.uses>=i.max_uses THEN 'exhausted' ELSE 'active' END) FROM registration_invites i LEFT JOIN users u ON u.id=i.created_by WHERE ($1::uuid IS NULL OR (i.created_at,i.id)<(SELECT created_at,id FROM registration_invites WHERE id=$1)) ORDER BY i.created_at DESC,i.id DESC LIMIT 101")
        .bind(page.before).fetch_all(&state.db).await?;
    let more = rows.len() > 100;
    let items: Vec<_> = rows.into_iter().take(100).collect();
    let next = if more {
        items.last().and_then(|i| i.get("id")).cloned()
    } else {
        None
    };
    Ok(Json(json!({"items":items,"next_before":next})))
}
pub async fn revoke_invite(
    State(state): State<AppState>,
    admin: Admin,
    Path(id): Path<Uuid>,
) -> Result<Json<Value>, AppError> {
    let mut tx = state.db.begin().await?;
    let changed = sqlx::query(
        "UPDATE registration_invites SET revoked_at=COALESCE(revoked_at,now()) WHERE id=$1",
    )
    .bind(id)
    .execute(&mut *tx)
    .await?;
    if changed.rows_affected() == 0 {
        return Err(AppError::NotFound("Invite not found.".into()));
    }
    sqlx::query("INSERT INTO admin_audit(actor,action,detail) VALUES($1,'invite:revoke',jsonb_build_object('invite_id',$2::uuid))")
        .bind(admin.user).bind(id).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(json!({"ok":true})))
}

fn split_invite(code: Option<&str>) -> (Uuid, &str) {
    code.filter(|c| c.len() <= 128)
        .and_then(|c| c.trim().split_once('.'))
        .and_then(|(id, secret)| Uuid::parse_str(id).ok().map(|id| (id, secret)))
        .unwrap_or((Uuid::nil(), ""))
}

/// Preliminary check rejects invalid codes before expensive password hashing.
/// The authoritative check/consumption below repeats under a row lock.
pub async fn check_invite(db: &PgPool, code: Option<&str>) -> Result<(), AppError> {
    let (id, secret) = split_invite(code);
    let row: Option<(Vec<u8>, bool)> = sqlx::query_as("SELECT code_hash,revoked_at IS NULL AND expires_at>now() AND uses<max_uses FROM registration_invites WHERE id=$1")
        .bind(id).fetch_optional(db).await?;
    let valid = matches_code(
        secret,
        row.as_ref().map(|r| r.0.as_slice()).unwrap_or(&[0; 32]),
    );
    if !valid || !row.is_some_and(|r| r.1) {
        return Err(if code.is_none_or(|c| c.trim().is_empty()) {
            AppError::InviteRequired
        } else {
            AppError::InviteInvalid
        });
    }
    Ok(())
}

pub async fn consume_invite(
    tx: &mut Transaction<'_, Postgres>,
    code: Option<&str>,
) -> Result<Option<Uuid>, AppError> {
    // Policy edits take a conflicting lock, so mode/closure changes serialize
    // with registration and take effect for the next admitted transaction.
    let (open, mode): (bool, String) = sqlx::query_as(
        "SELECT registration_open,registration_mode FROM server_policy WHERE id=true FOR SHARE",
    )
    .fetch_one(&mut **tx)
    .await?;
    if !open {
        return Err(AppError::Conflict(
            "Registration is temporarily closed by the server operator.".into(),
        ));
    }
    if mode == "open" {
        return Ok(None);
    }
    let (id, secret) = split_invite(code);
    let row: Option<Vec<u8>> =
        sqlx::query_scalar("SELECT code_hash FROM registration_invites WHERE id=$1 FOR UPDATE")
            .bind(id)
            .fetch_optional(&mut **tx)
            .await?;
    let valid = matches_code(secret, row.as_deref().unwrap_or(&[0; 32]));
    if !valid || row.is_none() {
        return Err(if code.is_none_or(|c| c.trim().is_empty()) {
            AppError::InviteRequired
        } else {
            AppError::InviteInvalid
        });
    }
    let updated = sqlx::query("UPDATE registration_invites SET uses=uses+1 WHERE id=$1 AND revoked_at IS NULL AND expires_at>clock_timestamp() AND uses<max_uses")
        .bind(id).execute(&mut **tx).await?;
    if updated.rows_affected() != 1 {
        return Err(AppError::InviteInvalid);
    }
    Ok(Some(id))
}

pub async fn audit_invite_refusal(db: &PgPool) -> Result<(), AppError> {
    sqlx::query("INSERT INTO admin_audit(action) VALUES('invite:register-refused')")
        .execute(db)
        .await?;
    Ok(())
}

pub async fn self_check(
    State(state): State<AppState>,
    admin: Admin,
) -> Result<Json<Value>, AppError> {
    manager(&admin)?;
    if !rate_limit::try_enforce_key(&state, "self-check", &admin.user.to_string(), 12, 60).await? {
        return Err(AppError::RateLimited);
    }
    let migration: Option<i64> =
        sqlx::query_scalar("SELECT max(version) FROM _sqlx_migrations WHERE success")
            .fetch_one(&state.db)
            .await?;
    let mut result = configuration_check(&state.config.turn, state.config.fcm.enabled(), |key| {
        std::env::var(key).ok()
    });
    result["setup"] = json!({"available":available(&state.db).await?});
    result["server"] = json!({"version":env!("CARGO_PKG_VERSION"),"migration_level":migration});
    let storage = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        state.s3.check_reachable(),
    )
    .await;
    result["storage"] = json!({"reachable":matches!(storage, Ok(Ok(()))),"client_uploads_ready":state.s3.require_client_uploads().is_ok()});
    let mut providers = state.push.providers_for("new_message");
    providers.extend(state.push.providers_for("incoming_call"));
    providers.sort();
    providers.dedup();
    result["push"]["status"] = json!(if result["push"]["offline"] == true {
        "offline"
    } else if providers.is_empty() {
        "not_configured"
    } else {
        "configured"
    });
    result["push"]["active_providers"] = json!(providers);
    let disk = tokio::task::spawn_blocking(|| disk_space(&data_dir()))
        .await
        .ok()
        .flatten();
    result["disk"] = json!({"available_bytes":disk,"status":if disk.is_some() {"ok"} else {"unknown"},"low_space":disk.map(|b|b<1024*1024*1024)});
    Ok(Json(result))
}

mod addresses;

/// Configuration-only: never probes the public URL/TURN/LiveKit/push services,
/// returns secrets, reads credential files, or claims external reachability.
fn configuration_check(
    turn: &crate::config::TurnConfig,
    fcm_configured: bool,
    get: impl Fn(&str) -> Option<String>,
) -> Value {
    let url = get("PUBLIC_API_ORIGIN").and_then(|u| reqwest::Url::parse(&u).ok());
    let url_sane = url.as_ref().is_some_and(|u| {
        u.scheme() == "https"
            && u.host_str().is_some()
            && u.username().is_empty()
            && u.password().is_none()
            && u.query().is_none()
            && u.fragment().is_none()
            && u.path() == "/"
            && u.port_or_known_default().is_some_and(|p| p > 0)
    });
    let external_ip_set = get("TURN_EXTERNAL_IP").is_some_and(|v| {
        v.parse::<std::net::IpAddr>().is_ok_and(|ip| match ip {
            std::net::IpAddr::V4(v) => {
                !v.is_unspecified()
                    && !v.is_loopback()
                    && !v.is_private()
                    && !v.is_link_local()
                    && !v.is_multicast()
                    && !v.is_broadcast()
                    && !(v.octets()[0] == 100 && (64..=127).contains(&v.octets()[1]))
            }
            std::net::IpAddr::V6(v) => {
                !v.is_unspecified()
                    && !v.is_loopback()
                    && !v.is_unique_local()
                    && !v.is_unicast_link_local()
                    && !v.is_multicast()
                    && v.to_ipv4_mapped().is_none()
            }
        })
    });
    let ports_sane = !turn.turn_urls.is_empty() && turn.turn_urls.iter().all(|s| sane_turn_url(s));
    let min = get("TURN_MIN_PORT").and_then(|p| p.parse::<u16>().ok());
    let max = get("TURN_MAX_PORT").and_then(|p| p.parse::<u16>().ok());
    let relay_sane = min.zip(max).is_some_and(|(a, b)| a > 0 && a <= b);
    let secret_set = turn.shared_secret.trim().len() >= 32
        && turn.shared_secret != "dev_turn_shared_secret_change_me";
    let rooms = [
        "CONFERENCE_LIVEKIT_URL",
        "CONFERENCE_LIVEKIT_API_URL",
        "CONFERENCE_LIVEKIT_API_KEY",
        "CONFERENCE_LIVEKIT_API_SECRET",
    ];
    let room_count = rooms
        .iter()
        .filter(|k| get(k).is_some_and(|v| !v.trim().is_empty()))
        .count();
    let offline = ["UNYRI_OFFLINE", "COCO_OFFLINE"]
        .iter()
        .any(|k| get(k).is_some_and(|v| v == "1" || v.eq_ignore_ascii_case("true")));
    let production = get("COCO_ENV").map(|v| v == "production").unwrap_or(!cfg!(debug_assertions));
    let rooms_enabled = crate::conferences::configuration_enabled(production, &get);
    json!({"public_url":{"url":if url_sane {url.map(|u|u.to_string())} else {None},"configured":url_sane,"tls":"not_probed"},
        "turn":{"external_ip_set":external_ip_set,"secret_set":secret_set,"urls_sane":ports_sane,"relay_ports_sane":relay_sane,"relay_min_port":min,"relay_max_port":max,"sane":external_ip_set && secret_set && ports_sane && relay_sane,"reachability":"not_probed"},
        "rooms":{"configured":room_count==rooms.len(),"status":if room_count==0 {"not_configured"} else if room_count==rooms.len() {"configured"} else {"incomplete"},"reachability":"not_probed"},
        "push":{"offline":offline,"fcm_configured":fcm_configured,"delivery":"not_probed"},
        "addresses":addresses::snapshot(turn, &get, rooms_enabled)})
}

fn sane_turn_url(value: &str) -> bool {
    let Some((scheme, rest)) = value.split_once(':') else {
        return false;
    };
    if !matches!(scheme, "turn" | "turns") {
        return false;
    }
    let authority = rest.split('?').next().unwrap_or("");
    if rest
        .split_once('?')
        .is_some_and(|(_, query)| !matches!(query, "transport=udp" | "transport=tcp"))
    {
        return false;
    }
    reqwest::Url::parse(&format!("https://{authority}")).is_ok_and(|u| {
        u.host_str().is_some()
            && u.username().is_empty()
            && u.password().is_none()
            && u.path() == "/"
            && u.fragment().is_none()
            && u.port_or_known_default().is_some_and(|p| p > 0)
    }) && authority
        .rsplit_once(':')
        .is_some_and(|(_, p)| p.parse::<u16>().is_ok_and(|p| p > 0))
}

#[cfg(windows)]
fn disk_space(path: &FsPath) -> Option<u64> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    extern "system" {
        fn GetDiskFreeSpaceExW(
            path: *const u16,
            available: *mut u64,
            total: *mut u64,
            free: *mut u64,
        ) -> i32;
    }
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let mut available = 0;
    // SAFETY: null-terminated path and valid output pointer for this call.
    (unsafe {
        GetDiskFreeSpaceExW(
            wide.as_ptr(),
            &mut available,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
        )
    } != 0)
        .then_some(available)
}
#[cfg(unix)]
fn disk_space(path: &FsPath) -> Option<u64> {
    use std::os::unix::ffi::OsStrExt;
    let path = std::ffi::CString::new(path.as_os_str().as_bytes()).ok()?;
    let mut stats = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    // SAFETY: statvfs initializes the output on success; both pointers are valid.
    if unsafe { libc::statvfs(path.as_ptr(), stats.as_mut_ptr()) } != 0 {
        return None;
    }
    let stats = unsafe { stats.assume_init() };
    Some((stats.f_bavail as u64).saturating_mul(stats.f_frsize as u64))
}
#[cfg(not(any(unix, windows)))]
fn disk_space(_: &FsPath) -> Option<u64> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn installer_server_names_are_bounded_and_printable() {
        assert_eq!(valid_server_name("  Family server ").as_deref(), Some("Family server"));
        assert_eq!(valid_server_name("Сервер семьи").as_deref(), Some("Сервер семьи"));
        assert_eq!(valid_server_name(&"я".repeat(64)).map(|n| n.chars().count()), Some(64));
        assert!(valid_server_name("").is_none());
        assert!(valid_server_name("   ").is_none());
        assert!(valid_server_name(&"a".repeat(65)).is_none());
        assert!(valid_server_name("bad\nname").is_none());
        assert!(valid_server_name("bad\u{7}name").is_none());
        // 64 four-byte characters exceed the console's 200-byte limit.
        assert!(valid_server_name(&"🙂".repeat(64)).is_none());
    }
    #[test]
    fn codes_are_short_random_and_normalized() {
        let a = generate_code();
        let b = generate_code();
        assert_eq!(a.len(), 19);
        assert_ne!(a, b);
        assert!(a
            .split('-')
            .all(|g| g.len() == 4 && g.bytes().all(|b| ALPHABET.contains(&b))));
        assert!(matches_code(
            &a.to_lowercase().replace('-', " "),
            &code_hash(&a)
        ));
        assert!(!matches_code(&b, &code_hash(&a)));
        assert!(!matches_code(&a, &[0; 32]));
    }
    #[test]
    fn invite_defaults_and_bounds() {
        assert_eq!(
            NewInvite {
                max_uses: None,
                expires_in_seconds: None
            }
            .limits()
            .unwrap(),
            (1, 604800)
        );
        for uses in [0, 10001] {
            assert!(NewInvite {
                max_uses: Some(uses),
                expires_in_seconds: None
            }
            .limits()
            .is_err());
        }
        for seconds in [0, 59, 31536001] {
            assert!(NewInvite {
                max_uses: None,
                expires_in_seconds: Some(seconds)
            }
            .limits()
            .is_err());
        }
        assert_eq!(split_invite(Some("bad")), (Uuid::nil(), ""));
        assert!(serde_json::from_value::<RegistrationPolicy>(
            json!({"registration_mode":"closed"})
        )
        .is_err());
        assert_eq!(
            serde_json::from_value::<RegistrationPolicy>(
                json!({"registration_mode":"invite_only"})
            )
            .unwrap()
            .registration_mode
            .as_str(),
            "invite_only"
        );
    }
    #[test]
    fn turn_urls_require_valid_ports_and_no_credentials() {
        for u in [
            "turn:example.org:3478?transport=udp",
            "turns:[2001:db8::1]:5349?transport=tcp",
        ] {
            assert!(sane_turn_url(u));
        }
        for u in [
            "stun:x:3478",
            "turn:x:0",
            "turn:x:65536",
            "turn:user:password@x:3478",
            "turn:x",
        ] {
            assert!(!sane_turn_url(u));
        }
    }
    #[test]
    fn setup_file_is_private_and_replaced_without_temp_leaks() {
        let dir = std::env::temp_dir().join(format!("sh2-{}", Uuid::new_v4()));
        let path = dir.join("setup");
        write_private_code(&path, "first").unwrap();
        write_private_code(&path, "second").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "second\n");
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 1);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        remove_setup_file(&path);
        assert!(!path.exists());
        std::fs::remove_dir(dir).unwrap();
    }
    #[test]
    fn self_check_distinguishes_missing_and_invalid_settings_without_secrets() {
        let turn = crate::config::TurnConfig {
            stun_urls: vec![],
            turn_urls: vec!["turn:example.test:3478?transport=udp".into()],
            shared_secret: "very-private-fixture-value-32-bytes".into(),
            credential_ttl_seconds: 1200,
        };
        let missing = configuration_check(&turn, false, |_| None);
        assert_eq!(missing["public_url"]["configured"], false);
        assert_eq!(missing["turn"]["sane"], false);
        assert_eq!(missing["rooms"]["status"], "not_configured");
        let values = std::collections::HashMap::from([
            ("PUBLIC_API_ORIGIN", "https://example.test"),
            ("TURN_EXTERNAL_IP", "203.0.113.8"),
            ("TURN_MIN_PORT", "49152"),
            ("TURN_MAX_PORT", "49200"),
            ("UNYRI_OFFLINE", "true"),
            ("CONFERENCE_LIVEKIT_URL", "wss://rooms.example.test"),
        ]);
        let configured = configuration_check(&turn, true, |k| values.get(k).map(|v| v.to_string()));
        assert_eq!(configured["public_url"]["configured"], true);
        assert_eq!(configured["turn"]["sane"], true);
        assert_eq!(configured["rooms"]["status"], "incomplete");
        assert!(configured["addresses"]["rooms"]["enabled"].is_null());
        assert_eq!(configured["push"]["offline"], true);
        assert!(!configured.to_string().contains(&turn.shared_secret));
        for ip in [
            "127.0.0.1",
            "192.168.1.1",
            "100.64.0.1",
            "::1",
            "fe80::1",
            "::ffff:127.0.0.1",
        ] {
            let bad = configuration_check(&turn, false, |k| {
                if k == "TURN_EXTERNAL_IP" {
                    Some(ip.into())
                } else {
                    None
                }
            });
            assert_eq!(bad["turn"]["external_ip_set"], false);
        }
        let bad = configuration_check(&turn, false, |k| match k {
            "PUBLIC_API_ORIGIN" => Some("https://user:password@example.test/?token=private".into()),
            "TURN_MIN_PORT" => Some("60000".into()),
            "TURN_MAX_PORT" => Some("50000".into()),
            _ => None,
        });
        assert_eq!(bad["public_url"]["url"], Value::Null);
        assert_eq!(bad["turn"]["relay_ports_sane"], false);
        assert!(!bad.to_string().contains("password"));
        for (api, expected) in [("http://172.30.219.20:7880", json!(true)), ("invalid", Value::Null)] {
            let rooms = configuration_check(&turn, false, |k| match k {
                "COCO_ENV" => Some("production".into()),
                "CONFERENCE_LIVEKIT_URL" => Some("wss://rooms.example.test".into()),
                "CONFERENCE_LIVEKIT_API_URL" => Some(api.into()),
                "CONFERENCE_LIVEKIT_API_KEY" => Some("fixture-only-key".into()),
                "CONFERENCE_LIVEKIT_API_SECRET" => Some("fixture-only-secret-of-at-least-32-bytes".into()),
                "CONFERENCE_LIVEKIT_AUTOCREATE_DISABLED" | "CONFERENCE_GATEWAY_REQUIRED" => Some("true".into()),
                _ => None,
            });
            assert_eq!(rooms["addresses"]["rooms"]["enabled"], expected);
        }
        assert!(disk_space(&std::env::temp_dir()).is_some());
    }
}

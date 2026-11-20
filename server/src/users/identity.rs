//! Public identity is separate from the legacy login (`users.username`).
use axum::{
    extract::{Path, Query, State},
    Json,
};
use chrono::{DateTime, Utc};
use rand::{rngs::OsRng, Rng, RngCore};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{
    auth::{
        middleware::{lock_active_session, AuthUser},
        rate_limit,
    },
    config::identity::IdentityQuota,
    error::AppError,
    net::ClientIp,
    state::AppState,
    users::models::Patch,
};

pub const NUMBER_ATTEMPTS: usize = 128;
pub const RENAME_DAYS: i64 = 7;
pub const FREEZE_DAYS: i64 = 30;

pub fn routes() -> axum::Router<AppState> {
    axum::Router::new()
        .route("/users/me/identity", axum::routing::get(get).put(put))
        .route("/users/lookup", axum::routing::get(lookup))
        .route("/users/:user_id/profile", axum::routing::get(profile))
        .layer(axum::extract::DefaultBodyLimit::max(8192))
        .layer(axum::middleware::map_response(
            |mut response: axum::response::Response| async move {
                response.headers_mut().insert(
                    axum::http::header::CACHE_CONTROL,
                    axum::http::HeaderValue::from_static("no-store"),
                );
                response
            },
        ))
}

/// Rejection sampling; identical to the migration fallback for old writers.
fn number_from_sample(sample: u32) -> Option<i64> {
    (sample < 3_600_000_000).then_some(100_000_000 + i64::from(sample) % 900_000_000)
}

pub async fn allocate_number(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user: Uuid,
) -> Result<i64, AppError> {
    for _ in 0..NUMBER_ATTEMPTS {
        let Some(number) = number_from_sample(OsRng.next_u32()) else {
            continue;
        };
        let inserted: Option<i64> = sqlx::query_scalar(
            "INSERT INTO public_numbers(number,user_id,canonical) SELECT $1,$2,true \
             WHERE NOT EXISTS(SELECT 1 FROM public_number_ranges WHERE $1 BETWEEN first_number AND last_number) \
             ON CONFLICT DO NOTHING RETURNING number")
            .bind(number).bind(user).fetch_optional(&mut **tx).await?;
        if let Some(number) = inserted {
            return Ok(number);
        }
    }
    Err(AppError::Unavailable(
        "Public number allocation unavailable. Retry later.".into(),
    ))
}

pub fn normalize_username(raw: &str) -> Result<String, AppError> {
    if !(5..=32).contains(&raw.len()) || !valid_token(raw) {
        return Err(AppError::Validation("Public username must be 5-32 ASCII letters, digits or underscore, start with a letter, and contain no double underscore.".into()));
    }
    Ok(raw.to_ascii_lowercase())
}

pub(super) fn valid_token(raw: &str) -> bool {
    (1..=32).contains(&raw.len())
        && raw.as_bytes()[0].is_ascii_alphabetic()
        && raw.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        && !raw.contains("__")
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, sqlx::Type)]
#[serde(rename_all = "snake_case")]
#[sqlx(type_name = "text", rename_all = "snake_case")]
pub enum Discoverability {
    Everyone,
    Contacts,
    Nobody,
}

#[derive(Serialize, sqlx::FromRow)]
pub struct Identity {
    pub user_id: Uuid,
    pub unyri_number: String,
    pub public_username: Option<String>,
    pub discoverability: Discoverability,
    pub revision: i64,
    pub username_changed_at: Option<DateTime<Utc>>,
}

#[derive(Serialize)]
pub struct OwnerIdentity {
    #[serde(flatten)]
    identity: Identity,
    public_origin: String,
}

fn owner_response(state: &AppState, identity: Identity) -> Json<OwnerIdentity> {
    Json(OwnerIdentity {
        identity,
        public_origin: state.config.identity.public_origin.clone(),
    })
}
const OWN: &str = "SELECT user_id,unyri_number::text,public_username,discoverability,revision,username_changed_at FROM user_identities WHERE user_id=$1";

async fn own(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    user: Uuid,
) -> Result<Identity, AppError> {
    if !super::identity_backfill::ensure(tx, user).await? {
        return Err(AppError::Unauthorized);
    }
    Ok(sqlx::query_as(OWN).bind(user).fetch_one(&mut **tx).await?)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UpdateIdentity {
    expected_revision: i64,
    #[serde(default)]
    public_username: Patch<String>,
    discoverability: Option<Discoverability>,
}

/// All budgets have independent user and network identities, never a user+IP key.
pub(super) async fn budget(
    state: &AppState,
    auth: &AuthUser,
    ip: std::net::IpAddr,
    scope: &str,
    quota: IdentityQuota,
    window: u64,
) -> Result<(), AppError> {
    budget_for_user(state, auth.user_id, ip, scope, quota, window).await
}

pub(super) async fn budget_for_user(
    state: &AppState,
    user: Uuid,
    ip: std::net::IpAddr,
    scope: &str,
    quota: IdentityQuota,
    window: u64,
) -> Result<(), AppError> {
    let user_ok = rate_limit::try_enforce_key(
        state,
        &format!("identity:{scope}:user"),
        &user.to_string(),
        quota.user.into(),
        window,
    )
    .await?;
    let ip_ok = rate_limit::try_enforce_key(
        state,
        &format!("identity:{scope}:ip"),
        &rate_limit::auth_client_key(ip),
        quota.ip.into(),
        window,
    )
    .await?;
    if user_ok && ip_ok {
        Ok(())
    } else {
        Err(AppError::RateLimited)
    }
}

pub async fn get(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
) -> Result<Json<OwnerIdentity>, AppError> {
    budget(&state, &auth, ip, "read", state.config.identity.read, 60).await?;
    let mut tx = state.db.begin().await?;
    let result = own(&mut tx, auth.user_id).await?;
    tx.commit().await?;
    Ok(owner_response(&state, result))
}

pub async fn put(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    request: Result<Json<UpdateIdentity>, axum::extract::rejection::JsonRejection>,
) -> Result<Json<OwnerIdentity>, AppError> {
    budget(&state, &auth, ip, "write", state.config.identity.write, 60).await?;
    let Json(req) =
        request.map_err(|_| AppError::Validation("Invalid identity settings request.".into()))?;
    let requested = match req.public_username {
        Patch::Absent => None,
        Patch::Null => Some(None),
        Patch::Value(name) => Some(Some(normalize_username(&name)?)),
    };
    let mut tx = state.db.begin().await?;
    lock_active_session(&mut tx, &auth).await?;
    let current = own(&mut tx, auth.user_id).await?;
    if current.revision != req.expected_revision {
        return Err(AppError::Conflict(
            "Identity settings changed. Reload and try again.".into(),
        ));
    }
    let next = requested.unwrap_or_else(|| current.public_username.clone());
    let policy = req.discoverability.unwrap_or(current.discoverability);
    let rename = next != current.public_username;
    if rename {
        let read_only: bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM admin_user_controls WHERE user_id=$1 AND read_only_reason IS NOT NULL AND (read_only_until IS NULL OR read_only_until>now()))")
            .bind(auth.user_id).fetch_one(&mut *tx).await?;
        if read_only {
            return Err(AppError::AccountReadOnly);
        }
        let allowed: bool = sqlx::query_scalar(
            "SELECT $1::timestamptz IS NULL OR $1<=now()-make_interval(days=>$2)",
        )
        .bind(current.username_changed_at)
        .bind(RENAME_DAYS as i32)
        .fetch_one(&mut *tx)
        .await?;
        if !allowed {
            return Err(AppError::RateLimited);
        }
        // Lock old/new namespace rows in lexical order to avoid cross-renames
        // deadlocking; never acquire an account lock after a namespace lock.
        let mut names: Vec<&str> = current
            .public_username
            .iter()
            .chain(next.iter())
            .map(String::as_str)
            .collect();
        names.sort_unstable();
        names.dedup();
        for name in &names {
            sqlx::query("INSERT INTO public_usernames(name) VALUES($1) ON CONFLICT DO NOTHING")
                .bind(name)
                .execute(&mut *tx)
                .await?;
        }
        for name in &names {
            sqlx::query("SELECT name FROM public_usernames WHERE name=$1 FOR UPDATE")
                .bind(name)
                .execute(&mut *tx)
                .await?;
        }
        if let Some(name) = &next {
            let (reserved,unavailable): (bool,bool)=sqlx::query_as("SELECT reserved,user_id IS NOT NULL OR COALESCE(frozen_until>now(),false) FROM public_usernames WHERE name=$1")
                .bind(name).fetch_one(&mut *tx).await?;
            if reserved || unavailable {
                return Err(AppError::PublicUsernameUnavailable);
            }
        }
        if let Some(name) = &current.public_username {
            sqlx::query("UPDATE public_usernames SET user_id=NULL,frozen_until=now()+make_interval(days=>$2) WHERE name=$1")
                .bind(name).bind(FREEZE_DAYS as i32).execute(&mut *tx).await?;
        }
        if let Some(name) = &next {
            sqlx::query("UPDATE public_usernames SET user_id=$2,frozen_until=NULL WHERE name=$1")
                .bind(name)
                .bind(auth.user_id)
                .execute(&mut *tx)
                .await?;
        }
    }
    let changed = rename || policy != current.discoverability;
    if changed {
        sqlx::query("UPDATE user_identities SET public_username=$2,discoverability=$3,revision=revision+1,username_changed_at=CASE WHEN $4 THEN now() ELSE username_changed_at END WHERE user_id=$1")
            .bind(auth.user_id).bind(&next).bind(policy).bind(rename).execute(&mut *tx).await?;
        sqlx::query("UPDATE users SET profile_version=profile_version+1 WHERE id=$1")
            .bind(auth.user_id)
            .execute(&mut *tx)
            .await?;
    }
    let result = sqlx::query_as(OWN)
        .bind(auth.user_id)
        .fetch_one(&mut *tx)
        .await?;
    tx.commit().await?;
    if changed {
        notify(&state, auth.user_id).await;
    }
    Ok(owner_response(&state, result))
}

pub(super) async fn notify(state: &AppState, user: Uuid) {
    if let Ok(version) =
        sqlx::query_scalar::<_, i64>("SELECT profile_version FROM users WHERE id=$1")
            .bind(user)
            .fetch_one(&state.db)
            .await
    {
        super::handlers::notify_profile_changed(state, user, version).await;
    }
}

#[derive(Deserialize)]
pub struct Lookup {
    number: Option<String>,
    username: Option<String>,
    #[serde(flatten)]
    extra: std::collections::HashMap<String, String>,
}

#[derive(Serialize, sqlx::FromRow)]
pub struct PublicProfile {
    id: Uuid,
    username: String,
    unyri_number: String,
    public_username: Option<String>,
    display_name: String,
    bio: Option<String>,
    avatar_url: Option<String>,
    has_avatar: bool,
    profile_version: i64,
    avatar_version: i64,
}

pub fn parse_number(raw: &str) -> Option<i64> {
    if raw.is_empty()
        || raw.len() > 9
        || raw.starts_with('0')
        || !raw.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    raw.parse().ok().filter(|n| (1..=999_999_999).contains(n))
}

pub async fn lookup(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    query: Result<Query<Lookup>, axum::extract::rejection::QueryRejection>,
) -> Result<Json<PublicProfile>, AppError> {
    let query = query.map(|Query(q)| q).unwrap_or_else(|_| Lookup {
        number: None,
        username: None,
        extra: Default::default(),
    });
    let (number, name) = if query.extra.is_empty() {
        match (query.number, query.username) {
            (Some(n), None) => (parse_number(&n), None),
            (None, Some(n)) => (
                None,
                normalize_username(n.strip_prefix('@').unwrap_or(&n)).ok(),
            ),
            _ => (None, None),
        }
    } else {
        (None, None)
    };
    resolve(&state, &auth, ip, number, name, None).await
}

pub async fn profile(
    State(state): State<AppState>,
    ClientIp(ip): ClientIp,
    auth: AuthUser,
    Path(id): Path<String>,
) -> Result<Json<PublicProfile>, AppError> {
    resolve(&state, &auth, ip, None, None, id.parse().ok()).await
}

async fn resolve(
    state: &AppState,
    auth: &AuthUser,
    ip: std::net::IpAddr,
    number: Option<i64>,
    name: Option<String>,
    id: Option<Uuid>,
) -> Result<Json<PublicProfile>, AppError> {
    budget(
        state,
        auth,
        ip,
        "discover",
        state.config.identity.lookup_minute,
        60,
    )
    .await?;
    budget(
        state,
        auth,
        ip,
        "discover-day",
        state.config.identity.lookup_day,
        86400,
    )
    .await?;
    let deadline =
        tokio::time::Instant::now() + std::time::Duration::from_millis(OsRng.gen_range(35..=55));
    let result: Result<Option<PublicProfile>, AppError> = async {
        let mut tx = state.db.begin().await?;
        // A missing identity has the migration defaults. Filter visibility before
        // any on-demand write, including for UUIDs known through legacy clients.
        let target: Option<Uuid> = sqlx::query_scalar(&format!(
            "SELECT u.id FROM users u LEFT JOIN user_identities i ON i.user_id=u.id {VISIBLE}"
        ))
        .bind(auth.user_id)
        .bind(auth.device_id)
        .bind(number)
        .bind(&name)
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(user) = target {
            super::identity_backfill::ensure(&mut tx, user).await?;
        }
        // Authoritative response and visibility come from this single snapshot.
        // Revalidate after provisioning: concurrent renames/privacy changes must
        // never mix fields or turn a frozen name into a redirect.
        let profile = sqlx::query_as::<_, PublicProfile>(&format!(
            "SELECT u.id,COALESCE(i.public_username,'') AS username,i.unyri_number::text,i.public_username,u.display_name,u.bio,u.avatar_url, \
             u.avatar_key IS NOT NULL AS has_avatar,u.profile_version,u.avatar_version \
             FROM user_identities i JOIN users u ON u.id=i.user_id {VISIBLE}"))
        .bind(auth.user_id)
        .bind(auth.device_id)
        .bind(number)
        .bind(&name)
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(profile)
    }
    .await;
    tokio::time::sleep_until(deadline).await;
    Ok(Json(result?.ok_or_else(|| {
        AppError::NotFound("User not found.".into())
    })?))
}

const VISIBLE: &str = "WHERE u.id=COALESCE($5::uuid, \
    (SELECT user_id FROM user_identities WHERE public_username=$4), \
    (SELECT user_id FROM public_numbers WHERE number=$3)) \
    AND u.deleted_at IS NULL AND NOT EXISTS(SELECT 1 FROM server_suspensions s WHERE s.user_id=u.id AND (s.until_date IS NULL OR s.until_date>now())) \
    AND NOT user_device_blocked($2,u.id) AND NOT user_pair_blocked($1,u.id) \
    AND (u.id=$1 OR COALESCE(i.discoverability,'everyone')='everyone' OR (i.discoverability='contacts' AND EXISTS( \
        SELECT 1 FROM user_contacts c WHERE c.owner_id=u.id AND c.contact_id=$1 AND c.removed_at IS NULL)))";

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn username_grammar_and_normalization() {
        for name in ["Alice_1", "abcde", &"a".repeat(32)] {
            assert_eq!(normalize_username(name).unwrap(), name.to_ascii_lowercase());
        }
        for name in [
            "",
            "abcd",
            "1abcd",
            "_abcd",
            "ab__cd",
            "a.bcd",
            "ab-cd",
            " abcd",
            "abcd ",
            "@alice",
            "аlice",
            "aliсe",
            "Ａlice",
            "alicé",
            "alice\u{200d}",
            &"a".repeat(33),
        ] {
            assert!(normalize_username(name).is_err(), "{name}");
        }
    }
    #[test]
    fn number_rejection_boundaries_and_format() {
        assert_eq!(number_from_sample(0), Some(100_000_000));
        assert_eq!(number_from_sample(899_999_999), Some(999_999_999));
        assert_eq!(number_from_sample(3_599_999_999), Some(999_999_999));
        assert_eq!(number_from_sample(3_600_000_000), None);
        assert_eq!(number_from_sample(u32::MAX), None);
        let mut numbers = std::collections::HashSet::new();
        for _ in 0..1000 {
            if let Some(number) = number_from_sample(OsRng.next_u32()) {
                assert_eq!(number.to_string().len(), 9);
                numbers.insert(number);
            }
        }
        assert!(numbers.len() > 700);
        for n in [
            "0",
            "01",
            "+123456789",
            "1234567890",
            "１２３４５６７８９",
            "1e8",
            " 123456789",
        ] {
            assert_eq!(parse_number(n), None);
        }
        assert_eq!(parse_number("123456789"), Some(123456789));
        assert_eq!(parse_number("1"), Some(1));
    }
}

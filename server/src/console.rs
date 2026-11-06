//! Server-hosted administration console (Admin Console Pack 3).
//!
//! The console is plain documents served by this server. It holds **no**
//! authority of its own: everything it shows comes from `/admin/*`, which still
//! requires the same administrator identity and the same security model as the
//! native clients. Knowing the console URL grants nothing.
//!
//! Two credentials exist, and neither is the native admin token:
//!
//! * a **handoff** - a single-use, short-lived secret minted by an unlocked
//!   native client and redeemable exactly once by a browser;
//! * a **console session** - an opaque token in an `HttpOnly` /
//!   `SameSite=Strict` cookie, plus a CSRF token the page must echo in a header.
//!
//! Both are foreign-keyed to the native `admin_sessions` row that authorized
//! them, so lock, key rotation, 2FA reset, role revoke, logout, suspension and
//! account deletion all destroy console authority by cascade. No revocation rule
//! is re-implemented here.

use axum::{
    body::Body,
    extract::State,
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Duration, Utc};
use rand::RngCore;
use serde::Deserialize;
use serde_json::{json, Value};
use uuid::Uuid;

use crate::admin::{self, Admin, AdminChannel};
use crate::error::AppError;
use crate::state::AppState;

/// Session cookie. Not readable by page scripts, so a cross-site script cannot
/// exfiltrate it, and never sent on cross-site requests (`SameSite=Strict`).
pub const SESSION_COOKIE: &str = "coco_admin_console";
/// CSRF cookie. Deliberately readable by the page: the value must be echoed in
/// the `x-coco-csrf` header, which a cross-site request cannot do.
pub const CSRF_COOKIE: &str = "coco_console_csrf";
/// Header carrying the CSRF value on state-changing console requests.
pub const CSRF_HEADER: &str = "x-coco-csrf";

/// Console idle window. Shorter than the native absolute window: a browser is
/// left unlocked more often than a phone.
const IDLE_MINUTES: i64 = 15;
/// Console absolute window, independent of activity.
const ABSOLUTE_HOURS: i64 = 8;
/// A handoff is a credential for one exchange, so its life is very short.
const HANDOFF_SECONDS: i64 = 60;

fn denied() -> AppError {
    AppError::NotFound("Not found.".into())
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    base64::Engine::encode(&base64::engine::general_purpose::URL_SAFE_NO_PAD, bytes)
}

// ---------------------------------------------------------------------------
// static assets
//
// Embedded at compile time: the console ships with the server binary, so there
// is no build step, no node_modules, no CDN and no third-party script - which is
// also what makes the strict Content-Security-Policy below possible.
// ---------------------------------------------------------------------------

const SHELL: &str = include_str!("../console/index.html");
const STYLES: &str = include_str!("../console/app.css");
const SCRIPT: &str = include_str!("../console/app.js");

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/", get(shell))
        .route("/assets/app.css", get(styles))
        .route("/assets/app.js", get(script))
        .route("/handoff", post(redeem))
        .route("/session", get(session_info).delete(close))
        .layer(axum::extract::DefaultBodyLimit::max(4096))
        .layer(axum::middleware::map_response(security_headers))
}

/// Security headers for every console response (spec 7).
///
/// `default-src 'none'` with explicit allowances means an injected string cannot
/// load a script, open a socket or frame the page. There is no `unsafe-eval` and
/// no `unsafe-inline`: the console has no inline script or style at all.
pub async fn security_headers(mut response: Response) -> Response {
    let headers = response.headers_mut();
    for (name, value) in [
        (
            header::CONTENT_SECURITY_POLICY,
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; \
             connect-src 'self'; font-src 'self'; form-action 'none'; frame-ancestors 'none'; \
             base-uri 'none'; object-src 'none'",
        ),
        (header::X_FRAME_OPTIONS, "DENY"),
        (header::REFERRER_POLICY, "no-referrer"),
        (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
        (header::CACHE_CONTROL, "no-store"),
        (
            axum::http::HeaderName::from_static("permissions-policy"),
            "accelerometer=(), autoplay=(), camera=(), display-capture=(), encrypted-media=(), \
             geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()",
        ),
        (
            axum::http::HeaderName::from_static("cross-origin-opener-policy"),
            "same-origin",
        ),
        (
            axum::http::HeaderName::from_static("cross-origin-resource-policy"),
            "same-origin",
        ),
    ] {
        headers.insert(name, HeaderValue::from_static(value));
    }
    response
}

fn document(body: &'static str, content_type: &'static str) -> Response {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .body(Body::from(body))
        .expect("static response")
}

/// The console shell. Contains no privileged data of any kind.
async fn shell() -> Response {
    document(SHELL, "text/html; charset=utf-8")
}

async fn styles() -> Response {
    document(STYLES, "text/css; charset=utf-8")
}

async fn script() -> Response {
    document(SCRIPT, "text/javascript; charset=utf-8")
}

// ---------------------------------------------------------------------------
// cookies
// ---------------------------------------------------------------------------

pub fn read_cookie(headers: &HeaderMap, name: &str) -> Option<String> {
    let raw = headers.get(header::COOKIE)?.to_str().ok()?;
    for part in raw.split(';') {
        let part = part.trim();
        if let Some((key, value)) = part.split_once('=') {
            if key == name && !value.is_empty() && value.len() <= 128 {
                return Some(value.to_string());
            }
        }
    }
    None
}

fn set_cookie(name: &str, value: &str, http_only: bool, max_age: i64) -> String {
    let mut cookie = format!("{name}={value}; Path=/; Max-Age={max_age}; Secure; SameSite=Strict");
    if http_only {
        cookie.push_str("; HttpOnly");
    }
    cookie
}

fn clear_cookie(name: &str) -> String {
    format!("{name}=; Path=/; Max-Age=0; Secure; SameSite=Strict")
}

// ---------------------------------------------------------------------------
// same-origin enforcement
// ---------------------------------------------------------------------------

/// True when a state-changing, cookie-authenticated request provably came from
/// this very origin.
///
/// A browser always sends `Origin` on a non-GET fetch, so requiring it (with
/// `Referer` as the fallback) rejects cross-site form posts and cross-origin
/// fetches before any handler runs. `SameSite=Strict` and the CSRF header are
/// independent second and third barriers.
pub fn same_origin(headers: &HeaderMap) -> bool {
    let expected_host = headers
        .get(header::HOST)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.to_ascii_lowercase());
    let Some(expected_host) = expected_host else {
        return false;
    };
    let candidate = headers
        .get(header::ORIGIN)
        .and_then(|value| value.to_str().ok())
        .or_else(|| {
            headers
                .get(header::REFERER)
                .and_then(|value| value.to_str().ok())
        });
    let Some(candidate) = candidate else {
        return false;
    };
    let authority = candidate
        .split_once("://")
        .map(|(_, rest)| rest)
        .unwrap_or(candidate);
    let authority = authority.split(['/', '?', '#']).next().unwrap_or(authority);
    authority.eq_ignore_ascii_case(&expected_host)
}

// ---------------------------------------------------------------------------
// handoff issuance (native client, `x-coco-admin`)
// ---------------------------------------------------------------------------

/// `POST /admin/console-handoff`.
///
/// Only the native channel may mint a browser session: a console session cannot
/// create further console sessions, so a stolen browser cookie cannot be turned
/// into a durable foothold.
pub async fn issue_handoff(
    State(state): State<AppState>,
    admin: Admin,
) -> Result<Json<Value>, AppError> {
    if admin.channel != AdminChannel::Native {
        return Err(denied());
    }
    // Opportunistic sweep: a handoff that was never redeemed is worth nothing,
    // and a console session that idled out should not linger.
    sqlx::query(
        "DELETE FROM admin_console_handoffs WHERE expires_at<now() OR consumed_at IS NOT NULL",
    )
    .execute(&state.db)
    .await?;
    sqlx::query("DELETE FROM admin_console_sessions WHERE expires_at<now() OR last_seen<$1")
        .bind(Utc::now() - Duration::minutes(IDLE_MINUTES))
        .execute(&state.db)
        .await?;

    let token = random_token();
    let expires: DateTime<Utc> = sqlx::query_scalar(
        "INSERT INTO admin_console_handoffs(token_hash,user_id,auth_session,admin_session,role,expires_at)
         VALUES($1,$2,$3,$4,$5,$6) RETURNING expires_at",
    )
    .bind(admin::hash(&token))
    .bind(admin.user)
    .bind(admin.auth_session)
    .bind(&admin.token_hash)
    .bind(&admin.role)
    .bind(Utc::now() + Duration::seconds(HANDOFF_SECONDS))
    .fetch_one(&state.db)
    .await?;

    let mut tx = state.db.begin().await?;
    admin::audit_event(
        &mut tx,
        admin.user,
        "console:handoff-issued",
        Some(admin.user),
        "Administration console handoff issued",
        json!({"channel": "native"}),
    )
    .await?;
    tx.commit().await?;

    Ok(Json(json!({
        "handoff": token,
        "expires_at": expires,
        "expires_in": HANDOFF_SECONDS,
        // The console path is advertised so a client never has to guess it. It
        // carries no authority on its own.
        "console_path": "/administration",
    })))
}

// ---------------------------------------------------------------------------
// redemption (browser)
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
pub struct Redeem {
    handoff: String,
}

/// `POST /administration/handoff`.
///
/// The handoff is consumed by the same `UPDATE` that validates it, so a replayed
/// request cannot observe a still-usable row and two racing requests cannot both
/// win. Everything else is re-checked at redemption time: a handoff minted
/// seconds ago must not open a console for an account that has since been
/// suspended, signed out or had its key rotated.
pub async fn redeem(
    State(state): State<AppState>,
    Json(body): Json<Redeem>,
) -> Result<Response, AppError> {
    if body.handoff.len() != 43 {
        return Err(denied());
    }
    let mut tx = state.db.begin().await?;
    // Serialize distinct handoffs for the same parent. The parent lock also
    // coordinates with lock/policy changes; consumption and cookie creation
    // commit together, so a database failure does not destroy a usable handoff.
    let parent: Option<String> = sqlx::query_scalar(
        "SELECT s.token_hash FROM admin_sessions s JOIN admin_console_handoffs h ON h.admin_session=s.token_hash WHERE h.token_hash=$1 FOR UPDATE OF s"
    ).bind(admin::hash(&body.handoff)).fetch_optional(&mut *tx).await?;
    parent.ok_or_else(denied)?;
    let consumed: Option<(Uuid, Uuid, String, String)> = sqlx::query_as(
        "UPDATE admin_console_handoffs h SET consumed_at=now()
           FROM admin_sessions s, server_administrators a, sessions au, users u
          WHERE h.token_hash=$1 AND h.consumed_at IS NULL AND h.expires_at>now()
            AND s.token_hash=h.admin_session
            AND a.user_id=h.user_id AND s.auth_revision=a.auth_revision
            AND (a.auth_policy='paper_key' OR a.secret IS NOT NULL)
            AND (a.auth_policy='authenticator' OR (a.signing_public_key IS NOT NULL AND s.signing_public_key=a.signing_public_key))
            AND au.id=h.auth_session AND au.revoked_at IS NULL AND au.expires_at>now()
            AND u.id=h.user_id AND u.deleted_at IS NULL
            AND NOT EXISTS(SELECT 1 FROM server_suspensions b
                            WHERE b.user_id=h.user_id AND (b.until_date IS NULL OR b.until_date>now()))
            AND NOT EXISTS(SELECT 1 FROM devices d JOIN installation_suspensions b ON b.installation_id=d.installation_id
                            WHERE d.id=au.device_id AND (b.until_date IS NULL OR b.until_date>now()))
          RETURNING h.user_id, h.auth_session, h.admin_session, a.role",
    )
    .bind(admin::hash(&body.handoff))
    .fetch_optional(&mut *tx)
    .await?;
    let (user, auth_session, admin_session, role) = consumed.ok_or_else(denied)?;

    let token = random_token();
    let csrf = random_token();
    // One console session per native admin session: opening the console twice
    // replaces the previous browser session instead of leaving a second door.
    sqlx::query("DELETE FROM admin_console_sessions WHERE admin_session=$1")
        .bind(&admin_session)
        .execute(&mut *tx)
        .await?;
    sqlx::query(
        "INSERT INTO admin_console_sessions(token_hash,csrf_hash,user_id,auth_session,admin_session,role,expires_at)
         VALUES($1,$2,$3,$4,$5,$6,$7)",
    )
    .bind(admin::hash(&token))
    .bind(admin::hash(&csrf))
    .bind(user)
    .bind(auth_session)
    .bind(&admin_session)
    .bind(&role)
    .bind(Utc::now() + Duration::hours(ABSOLUTE_HOURS))
    .execute(&mut *tx)
    .await?;
    admin::audit_event(
        &mut tx,
        user,
        "console:session-open",
        Some(user),
        "Administration console opened",
        json!({"channel": "browser"}),
    )
    .await?;
    tx.commit().await?;

    let mut response = Json(
        json!({"role": role, "csrf": csrf, "idle_minutes": IDLE_MINUTES,
        "absolute_hours": ABSOLUTE_HOURS}),
    )
    .into_response();
    let headers = response.headers_mut();
    for cookie in [
        set_cookie(SESSION_COOKIE, &token, true, ABSOLUTE_HOURS * 3600),
        set_cookie(CSRF_COOKIE, &csrf, false, ABSOLUTE_HOURS * 3600),
    ] {
        headers.append(
            header::SET_COOKIE,
            HeaderValue::from_str(&cookie).map_err(|_| AppError::Internal)?,
        );
    }
    Ok(response)
}

/// `GET /administration/session` - what the page needs before rendering.
///
/// Answers for an unauthenticated browser too (with `authenticated: false`),
/// because "not signed in" is not privileged information and the page must be
/// able to say so without a console session.
pub async fn session_info(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, AppError> {
    let Some(token) = read_cookie(&headers, SESSION_COOKIE) else {
        return Ok(Json(json!({"authenticated": false})));
    };
    match validate(&state, &token).await {
        Ok(session) => Ok(Json(json!({
            "authenticated": true,
            "role": session.role,
            "idle_minutes": IDLE_MINUTES,
            "absolute_hours": ABSOLUTE_HOURS,
        }))),
        Err(_) => Ok(Json(json!({"authenticated": false}))),
    }
}

/// `DELETE /administration/session` - close this browser session only.
///
/// Deliberately not routed through `/admin/lock`: closing a console must not
/// sign the administrator's phone out of the native panel.
///
/// Closing is a state change, so it carries the same barriers as every other
/// console mutation - same origin and a CSRF value that hashes to the one stored
/// with the session. `SameSite=Strict` already keeps the cookie off cross-site
/// requests; this makes the endpoint self-defending rather than dependent on the
/// browser's behaviour alone.
pub async fn close(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, AppError> {
    let Some(token) = read_cookie(&headers, SESSION_COOKIE) else {
        return Err(denied());
    };
    if !same_origin(&headers) {
        return Err(denied());
    }
    let session = validate_for_close(&state, &token).await?;
    let presented = headers
        .get(CSRF_HEADER)
        .and_then(|value| value.to_str().ok())
        .filter(|value| value.len() == 43)
        .ok_or_else(denied)?;
    if admin::hash(presented) != session.1 {
        return Err(denied());
    }
    sqlx::query("DELETE FROM admin_console_sessions WHERE token_hash=$1")
        .bind(admin::hash(&token))
        .execute(&state.db)
        .await?;
    let mut tx = state.db.begin().await?;
    admin::audit_event(
        &mut tx,
        session.0,
        "console:session-close",
        Some(session.0),
        "Administration console closed",
        json!({"channel": "browser"}),
    )
    .await?;
    tx.commit().await?;
    let mut response = Json(json!({"ok": true})).into_response();
    let headers = response.headers_mut();
    for cookie in [clear_cookie(SESSION_COOKIE), clear_cookie(CSRF_COOKIE)] {
        headers.append(
            header::SET_COOKIE,
            HeaderValue::from_str(&cookie).map_err(|_| AppError::Internal)?,
        );
    }
    Ok(response)
}

// ---------------------------------------------------------------------------
// session validation (used by the `Admin` extractor for cookie requests)
// ---------------------------------------------------------------------------

pub struct ConsoleSession {
    pub user: Uuid,
    pub role: String,
    /// The native admin session this browser session derives from.
    pub admin_session: String,
    pub auth_session: Uuid,
    pub csrf_hash: String,
}

const VALIDATE_SQL: &str = "UPDATE admin_console_sessions c SET last_seen=now()
      FROM admin_sessions s, server_administrators a, sessions au, users u
     WHERE c.token_hash=$1
       AND s.token_hash=c.admin_session
       AND a.user_id=c.user_id AND s.auth_revision=a.auth_revision
       AND (a.auth_policy='paper_key' OR a.secret IS NOT NULL)
       AND (a.auth_policy='authenticator' OR (a.signing_public_key IS NOT NULL AND s.signing_public_key=a.signing_public_key))
       AND au.id=c.auth_session AND au.revoked_at IS NULL AND au.expires_at>now()
       AND u.id=c.user_id AND u.deleted_at IS NULL
       AND c.last_seen>$2 AND c.expires_at>now()
       AND NOT EXISTS(SELECT 1 FROM server_suspensions b
                       WHERE b.user_id=c.user_id AND (b.until_date IS NULL OR b.until_date>now()))
       AND NOT EXISTS(SELECT 1 FROM devices d JOIN installation_suspensions b ON b.installation_id=d.installation_id
                       WHERE d.id=au.device_id AND (b.until_date IS NULL OR b.until_date>now()))
     RETURNING c.user_id, a.role, c.admin_session, c.auth_session, c.csrf_hash";

/// Validate a console cookie and refresh its idle window.
///
/// The native admin session is required to exist but its own idle window is not
/// consulted: the console keeps its own timers, and existence is what carries
/// lock, rotation and revocation semantics through the foreign key.
pub async fn validate(state: &AppState, token: &str) -> Result<ConsoleSession, AppError> {
    if token.len() != 43 {
        return Err(denied());
    }
    let row: Option<(Uuid, String, String, Uuid, String)> = sqlx::query_as(VALIDATE_SQL)
        .bind(admin::hash(token))
        .bind(Utc::now() - Duration::minutes(IDLE_MINUTES))
        .fetch_optional(&state.db)
        .await?;
    let (user, role, admin_session, auth_session, csrf_hash) = row.ok_or_else(denied)?;
    Ok(ConsoleSession {
        user,
        role,
        admin_session,
        auth_session,
        csrf_hash,
    })
}

/// Same check without touching the idle window, so an explicit close still works
/// on a session that just went idle (the administrator asked to end it). Returns
/// the owner and the stored CSRF hash.
async fn validate_for_close(state: &AppState, token: &str) -> Result<(Uuid, String), AppError> {
    if token.len() != 43 {
        return Err(denied());
    }
    let row: Option<(Uuid, String)> = sqlx::query_as(
        "SELECT c.user_id, c.csrf_hash FROM admin_console_sessions c WHERE c.token_hash=$1 AND c.expires_at>now()",
    )
    .bind(admin::hash(token))
    .fetch_optional(&state.db)
    .await?;
    Ok(row.ok_or_else(denied)?)
}

/// Revokes every console session for one native admin session.
///
/// Used when the native client locks administration: the browser must not keep a
/// door open that the phone just closed. Normally unreachable because the lock
/// path deletes `admin_sessions` and the foreign key cascades, but a client that
/// locks by any other route still gets the correct behaviour.
pub async fn revoke_for_admin_session(
    db: &sqlx::PgPool,
    admin_session: &str,
) -> Result<u64, AppError> {
    let result = sqlx::query("DELETE FROM admin_console_sessions WHERE admin_session=$1")
        .bind(admin_session)
        .execute(db)
        .await?;
    Ok(result.rows_affected())
}

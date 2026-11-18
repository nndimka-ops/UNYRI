use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;

/// Uniform API error shape: `{"error": {"code": "...", "message": "..."}}`.
/// Variants intentionally avoid leaking whether a specific account exists.
#[derive(Debug)]
pub enum AppError {
    InviteRequired,
    InviteInvalid,
    SetupNotAvailable,
    SetupInvalid,
    Validation(String),
    CommonPassword(&'static str),
    DisplayNameReserved,
    DisplayNameLink,
    DisplayNameLeadingAt,
    UsernameReserved,
    UsernameLink,
    UsernameTaken,
    PublicUsernameUnavailable,
    TooManyDevices,
    InvalidCredentials,
    InstallationRestricted,
    InvalidRefreshToken,
    SessionRevoked,
    AccountReadOnly,
    Unauthorized,
    Forbidden(String),
    SettingsChanged,
    RecipientNotReady,
    RateLimited,
    NotFound(String),
    ChatNotFound,
    Gone(String),
    Conflict(String),
    PushRegistrationStale(i64),
    PushRegistrationRetired(i64),
    Unavailable(String),
    Internal,
}

#[derive(Serialize)]
struct ErrorBody {
    error: ErrorDetail,
}

#[derive(Serialize)]
struct ErrorDetail {
    code: String,
    message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_revision: Option<i64>,
}

impl AppError {
    fn parts(&self) -> (StatusCode, &'static str, String) {
        match self {
            AppError::InviteRequired => (StatusCode::FORBIDDEN, "invite_required", "An invitation code is required.".into()),
            AppError::InviteInvalid => (StatusCode::FORBIDDEN, "invite_invalid", "The invitation code is invalid or no longer available.".into()),
            AppError::SetupNotAvailable => (StatusCode::NOT_FOUND, "setup_not_available", "Owner setup is not available.".into()),
            AppError::SetupInvalid => (StatusCode::FORBIDDEN, "setup_invalid", "The setup code is invalid.".into()),
            AppError::Validation(msg) => (StatusCode::BAD_REQUEST, "validation_error", msg.clone()),
            AppError::CommonPassword(message) => (StatusCode::BAD_REQUEST, "common_password", (*message).into()),
            // The message doubles as the iOS string key.
            AppError::DisplayNameReserved => (
                StatusCode::BAD_REQUEST,
                "display_name_reserved",
                "This name is reserved for the service. Choose another name.".to_string(),
            ),
            AppError::DisplayNameLink => (StatusCode::BAD_REQUEST, "display_name_link",
                "Display name must not contain URLs or domains.".into()),
            AppError::DisplayNameLeadingAt => (StatusCode::BAD_REQUEST, "display_name_leading_at",
                "Display name must not start with '@'.".into()),
            AppError::UsernameReserved => (StatusCode::BAD_REQUEST, "username_reserved",
                "This username is reserved for the service. Choose another username.".into()),
            AppError::UsernameLink => (StatusCode::BAD_REQUEST, "username_link",
                "Username must not contain URLs or domains.".into()),
            AppError::TooManyDevices => (StatusCode::CONFLICT, "too_many_devices",
                "This account is signed in on too many devices. Sign out of an old device first.".into()),
            AppError::UsernameTaken => (
                StatusCode::CONFLICT,
                "username_taken",
                "Username is already taken.".to_string(),
            ),
            AppError::PublicUsernameUnavailable => (
                StatusCode::CONFLICT,
                "unavailable",
                "Public username is unavailable".into(),
            ),
            AppError::InstallationRestricted => (
                StatusCode::FORBIDDEN,
                "installation_restricted",
                "This app installation is restricted on this server. Contact its operator."
                    .to_string(),
            ),
            AppError::InvalidCredentials => (
                StatusCode::UNAUTHORIZED,
                "invalid_credentials",
                "Invalid username or password.".to_string(),
            ),
            AppError::InvalidRefreshToken => (
                StatusCode::UNAUTHORIZED,
                "invalid_refresh_token",
                "Refresh token is invalid or expired.".to_string(),
            ),
            AppError::SessionRevoked => (
                StatusCode::UNAUTHORIZED,
                "session_revoked",
                "Session has been revoked.".to_string(),
            ),
            AppError::AccountReadOnly => (
                StatusCode::FORBIDDEN,
                "account_read_only",
                "This account is temporarily in read-only mode. Contact the server operator."
                    .to_string(),
            ),
            AppError::Unauthorized => (
                StatusCode::UNAUTHORIZED,
                "unauthorized",
                "Authentication required.".to_string(),
            ),
            AppError::Forbidden(message) => (StatusCode::FORBIDDEN, "forbidden", message.clone()),
            AppError::SettingsChanged => (
                StatusCode::CONFLICT,
                "settings_changed",
                "Conference settings changed. Reload and try again.".into(),
            ),
            AppError::RecipientNotReady => (
                StatusCode::CONFLICT,
                "recipient_not_ready",
                "Recipient devices changed or are not ready. Refresh recipient keys and retry.".into(),
            ),
            AppError::RateLimited => (
                StatusCode::TOO_MANY_REQUESTS,
                "rate_limited",
                "Too many requests. Please try again later.".to_string(),
            ),
            AppError::NotFound(msg) => (StatusCode::NOT_FOUND, "not_found", msg.clone()),
            AppError::ChatNotFound => (
                StatusCode::NOT_FOUND,
                "chat_not_found",
                "Chat not found.".into(),
            ),
            AppError::Gone(msg) => (StatusCode::GONE, "gone", msg.clone()),
            AppError::Conflict(msg) => (StatusCode::CONFLICT, "conflict", msg.clone()),
            AppError::PushRegistrationStale(_) => (StatusCode::CONFLICT, "push_registration_stale", "Push registration has a newer revision.".into()),
            AppError::PushRegistrationRetired(_) => (StatusCode::CONFLICT, "push_registration_retired", "Push registration is retired.".into()),
            AppError::Unavailable(msg) => {
                (StatusCode::SERVICE_UNAVAILABLE, "unavailable", msg.clone())
            }
            AppError::Internal => (
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                "Internal server error.".to_string(),
            ),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let current_revision = match &self {
            Self::PushRegistrationStale(value) | Self::PushRegistrationRetired(value) => Some(*value),
            _ => None,
        };
        let (status, code, message) = self.parts();
        if matches!(self, AppError::Internal) {
            tracing::error!(code, "request failed with an internal error");
        }
        (
            status,
            Json(ErrorBody {
                error: ErrorDetail {
                    code: code.to_string(),
                    message,
                    current_revision,
                },
            }),
        )
            .into_response()
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        if let sqlx::Error::Database(ref db) = err {
            if db.code().as_deref()==Some("54000") && db.constraint()==Some("database_payload_budget") {
                return AppError::Validation("Database storage quota is full. Remove old history or contact the server operator.".into());
            }
        }
        tracing::error!(error = %err, "database error");
        AppError::Internal
    }
}

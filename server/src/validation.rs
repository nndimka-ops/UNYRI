use crate::error::AppError;

/// Shared username/display-name rules used by both registration and profile
/// updates, so the two code paths can never drift apart.
pub fn validate_username(username: &str) -> Result<(), AppError> {
    let len = username.chars().count();
    if !(3..=32).contains(&len) {
        return Err(AppError::Validation("Username must be 3-32 characters.".into()));
    }
    if !username.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.') {
        return Err(AppError::Validation(
            "Username may only contain letters, digits, '_' and '.'.".into(),
        ));
    }
    Ok(())
}

pub fn validate_display_name(display_name: &str) -> Result<&str, AppError> {
    let trimmed = display_name.trim();
    if trimmed.is_empty() || trimmed.chars().count() > 64 {
        return Err(AppError::Validation("Display name must be 1-64 characters.".into()));
    }
    Ok(trimmed)
}

/// Device label rules (Этап L.5): 1-64 chars after trimming; the client may
/// use its own default ("Android", "Bob's phone", ...) - the server only
/// bounds the length and never interprets the value.
pub fn validate_device_name(device_name: &str) -> Result<&str, AppError> {
    let trimmed = device_name.trim();
    if trimmed.is_empty() || trimmed.chars().count() > 64 {
        return Err(AppError::Validation("Device name must be 1-64 characters.".into()));
    }
    Ok(trimmed)
}

/// Bio rules (PROFILE.1): optional free text, at most 160 chars after
/// trimming. Empty is allowed (the handler maps it to a cleared/NULL bio).
pub fn validate_bio(bio: &str) -> Result<&str, AppError> {
    let trimmed = bio.trim();
    if trimmed.chars().count() > 160 {
        return Err(AppError::Validation("Bio must be at most 160 characters.".into()));
    }
    Ok(trimmed)
}

/// Avatars are an opaque reference (URL/key) at this stage - never raw image
/// bytes - so validation only guards against abuse of the text column.
pub fn validate_avatar_url(avatar_url: &str) -> Result<(), AppError> {
    if avatar_url.chars().count() > 2048 {
        return Err(AppError::Validation("Avatar URL must be at most 2048 characters.".into()));
    }
    if avatar_url.to_ascii_lowercase().starts_with("data:") {
        return Err(AppError::Validation("Avatar URL may not be a data: URI.".into()));
    }
    Ok(())
}

/// True if a Postgres error is a unique-constraint violation (SQLSTATE 23505).
pub fn is_unique_violation(err: &sqlx::Error) -> bool {
    matches!(err.as_database_error().and_then(|e| e.code()), Some(code) if code == "23505")
}

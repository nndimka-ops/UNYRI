use crate::error::AppError;

/// Shared username/display-name rules used by both registration and profile
/// updates, so the two code paths can never drift apart.
pub fn validate_username(username: &str) -> Result<(), AppError> {
    validate_username_for_role(username, false)
}

/// Staff may claim reserved identifiers; format/link restrictions apply to all.
pub fn validate_username_for_role(username: &str, official: bool) -> Result<(), AppError> {
    let len = username.chars().count();
    if !(3..=32).contains(&len) {
        return Err(AppError::Validation("Username must be 3-32 characters.".into()));
    }
    if !username.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.') {
        return Err(AppError::Validation(
            "Username may only contain letters, digits, '_' and '.'.".into(),
        ));
    }
    if crate::display_name::contains_link(username) { return Err(AppError::UsernameLink); }
    if !official { reject_reserved_username(username)?; }
    Ok(())
}

/// Username rules use brand fragments and exact staff tokens with optional numeric suffixes.
pub fn reject_reserved_username(username: &str) -> Result<(), AppError> {
    if crate::display_name::is_reserved_username(username) {
        return Err(AppError::UsernameReserved);
    }
    Ok(())
}

/// Returns the sanitised name to store (see `display_name::sanitize`).
pub fn validate_display_name(raw: &str) -> Result<String, AppError> {
    let name = crate::display_name::sanitize(raw);
    if !crate::display_name::is_valid(&name) {
        return Err(AppError::Validation("Display name must be 1-32 characters.".into()));
    }
    if crate::display_name::starts_with_at(&name) {
        return Err(AppError::DisplayNameLeadingAt);
    }
    if crate::display_name::contains_link(&name) {
        return Err(AppError::DisplayNameLink);
    }
    Ok(name)
}

pub fn reject_reserved_display_name(name: &str) -> Result<(), AppError> {
    if crate::display_name::is_reserved(name) {
        return Err(AppError::DisplayNameReserved);
    }
    Ok(())
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

/// Avatars are our own uploaded objects (`avatars/<key>`). External URLs are
/// refused: every phone that showed the profile fetched the chosen address,
/// leaking its IP to whoever set it and handing unvetted bytes to the image
/// decoder without a tap.
pub fn validate_avatar_url(avatar_url: &str) -> Result<(), AppError> {
    if avatar_url.chars().count() > 2048 {
        return Err(AppError::Validation("Avatar URL must be at most 2048 characters.".into()));
    }
    if !avatar_url.starts_with("avatars/") {
        return Err(AppError::Validation("Upload the avatar with the app.".into()));
    }
    Ok(())
}

/// True if a Postgres error is a unique-constraint violation (SQLSTATE 23505).
pub fn is_unique_violation(err: &sqlx::Error) -> bool {
    matches!(err.as_database_error().and_then(|e| e.code()), Some(code) if code == "23505")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_name_write_rules() {
        assert_eq!(validate_display_name("  Al\u{200B}ice  ").unwrap(), "Alice");
        assert!(validate_display_name(&"a".repeat(32)).is_ok());
        assert!(validate_display_name(&"😀".repeat(32)).is_ok());
        for raw in ["".to_string(), "\u{3164}".to_string(), "a".repeat(33), "a".repeat(64)] {
            assert!(matches!(validate_display_name(&raw), Err(AppError::Validation(message)) if message == "Display name must be 1-32 characters."));
        }
        assert!(matches!(validate_display_name("\u{200B}＠Alice"), Err(AppError::DisplayNameLeadingAt)));
        assert!(matches!(validate_display_name("site。com"), Err(AppError::DisplayNameLink)));
        assert!(matches!(reject_reserved_display_name("верификация"), Err(AppError::DisplayNameReserved)));
        assert!(reject_reserved_display_name("Support Team").is_ok());
    }

    #[test]
    fn username_write_rules() {
        for name in ["alice", "a_b.c", "SunnyRick"] { assert!(validate_username(name).is_ok()); }
        for name in ["admin", "support_team", "unyr1.user", "moderator123", "official", "security", "system", "bank", "payment", "verification"] {
            assert!(matches!(validate_username(name), Err(AppError::UsernameReserved)), "{name}");
        }
        for name in ["ab", "@alice", "имя", "alice smith"] {
            assert!(matches!(validate_username(name), Err(AppError::Validation(_))), "{name}");
        }
        assert!(validate_username(&"a".repeat(32)).is_ok());
        assert!(validate_username(&"a".repeat(33)).is_err());
        assert!(validate_username_for_role("admin", true).is_ok());
        assert!(validate_username_for_role("unyri_staff", true).is_ok());
        assert!(matches!(validate_username_for_role("example.com", true), Err(AppError::UsernameLink)));
        assert!(matches!(validate_username("example.com"), Err(AppError::UsernameLink)));
        for name in ["badminton_pro", "riverbank", "banker", "staffan", "stafford", "ecosystem", "spartak_supporter", "unofficial_ivan"] {
            assert!(validate_username(name).is_ok(), "{name}");
        }
    }

    #[tokio::test]
    async fn name_errors_keep_the_api_envelope_and_distinct_codes() {
        use axum::response::IntoResponse;
        for (error, code, message) in [
            (validate_display_name("").unwrap_err(), "validation_error", "Display name must be 1-32 characters."),
            (validate_display_name("a.com").unwrap_err(), "display_name_link", "Display name must not contain URLs or domains."),
            (validate_display_name("@alice").unwrap_err(), "display_name_leading_at", "Display name must not start with '@'."),
            (reject_reserved_display_name("system").unwrap_err(), "display_name_reserved", "This name is reserved for the service. Choose another name."),
            (validate_username("admin").unwrap_err(), "username_reserved", "This username is reserved for the service. Choose another username."),
        ] {
            let response = error.into_response();
            assert_eq!(response.status(), axum::http::StatusCode::BAD_REQUEST);
            let body = axum::body::to_bytes(response.into_body(), 4096).await.unwrap();
            let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
            assert_eq!(json, serde_json::json!({"error": {"code": code, "message": message}}));
        }
    }
}

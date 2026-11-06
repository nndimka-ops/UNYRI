//! Opaque, validated cursor pagination and filter whitelisting for the
//! administration lists.
//!
//! The cursor is `base64url(JSON)` and is deliberately **not** a capability:
//!
//! * every value it carries is bound as a SQL parameter, so no cursor content
//!   can ever reach SQL as text;
//! * it carries a fingerprint of the normalized filter set it was produced for,
//!   and a cursor presented with different filters is rejected outright;
//! * it can only move a page boundary inside the result set the caller is
//!   already authorized to read, with the filters already applied.
//!
//! A well-formed but tampered cursor therefore selects a *different page of the
//! same authorized data* - never different data, and never different filters.
use crate::error::AppError;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub const DEFAULT_LIMIT: i64 = 50;
pub const MAX_LIMIT: i64 = 100;
const MAX_KEY_LEN: usize = 64;
const MAX_ID_LEN: usize = 64;
const MAX_CURSOR_LEN: usize = 512;

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Payload {
    k: String,
    i: String,
    f: String,
}

/// Short, collision-resistant fingerprint of the normalized filter set.
pub fn fingerprint(parts: &[&str]) -> String {
    let mut digest = Sha256::new();
    for part in parts {
        digest.update((part.len() as u64).to_le_bytes());
        digest.update(part.as_bytes());
    }
    hex::encode(&digest.finalize()[..8])
}

pub fn encode(key: &str, id: &str, fingerprint: &str) -> String {
    let payload = Payload {
        k: key.to_string(),
        i: id.to_string(),
        f: fingerprint.to_string(),
    };
    match serde_json::to_vec(&payload) {
        Ok(bytes) => URL_SAFE_NO_PAD.encode(bytes),
        // Serializing three owned strings cannot fail; an empty cursor is
        // treated as "no cursor" by every caller.
        Err(_) => String::new(),
    }
}

/// Decodes a cursor, rejecting anything malformed, oversized, or produced for a
/// different filter set.
pub fn decode(cursor: &str, fingerprint: &str) -> Result<(String, String), AppError> {
    let bad = || AppError::Validation("Invalid page cursor.".into());
    if cursor.is_empty() || cursor.len() > MAX_CURSOR_LEN {
        return Err(bad());
    }
    let raw = URL_SAFE_NO_PAD.decode(cursor).map_err(|_| bad())?;
    let payload: Payload = serde_json::from_slice(&raw).map_err(|_| bad())?;
    if payload.k.is_empty()
        || payload.k.len() > MAX_KEY_LEN
        || payload.i.is_empty()
        || payload.i.len() > MAX_ID_LEN
        || payload.f != fingerprint
    {
        return Err(bad());
    }
    // Control characters have no legitimate use in a sort key or row id.
    if payload.k.chars().any(char::is_control) || payload.i.chars().any(char::is_control) {
        return Err(bad());
    }
    Ok((payload.k, payload.i))
}

pub fn limit(value: Option<i64>) -> Result<i64, AppError> {
    match value {
        None => Ok(DEFAULT_LIMIT),
        Some(value) if (1..=MAX_LIMIT).contains(&value) => Ok(value),
        Some(_) => Err(AppError::Validation(format!(
            "Page size must be between 1 and {MAX_LIMIT}."
        ))),
    }
}

/// Validates an enumerated filter against a fixed whitelist.
pub fn choice<'a>(
    value: Option<&'a str>,
    allowed: &[&str],
    field: &str,
) -> Result<Option<&'a str>, AppError> {
    match value.map(str::trim) {
        None | Some("") => Ok(None),
        Some(value) if allowed.contains(&value) => Ok(Some(value)),
        Some(_) => Err(AppError::Validation(format!("Unsupported {field} filter."))),
    }
}

/// Validates a free-text filter. The value is always bound as a parameter.
pub fn text<'a>(
    value: Option<&'a str>,
    max: usize,
    field: &str,
) -> Result<Option<&'a str>, AppError> {
    match value.map(str::trim) {
        None | Some("") => Ok(None),
        Some(value) if value.chars().count() <= max => Ok(Some(value)),
        Some(_) => Err(AppError::Validation(format!("{field} filter is too long."))),
    }
}

pub fn uuid(value: Option<&str>) -> Result<Option<Uuid>, AppError> {
    match value.map(str::trim) {
        None | Some("") => Ok(None),
        Some(value) => Uuid::parse_str(value)
            .map(Some)
            .map_err(|_| AppError::Validation("Invalid identifier filter.".into())),
    }
}

pub fn instant(value: Option<&str>) -> Result<Option<DateTime<Utc>>, AppError> {
    match value.map(str::trim) {
        None | Some("") => Ok(None),
        Some(value) => DateTime::parse_from_rfc3339(value)
            .map(|parsed| Some(parsed.with_timezone(&Utc)))
            .map_err(|_| AppError::Validation("Invalid date filter.".into())),
    }
}

/// Treats an absent or blank cursor as "first page".
fn present(cursor: Option<&str>) -> Option<&str> {
    cursor.map(str::trim).filter(|value| !value.is_empty())
}

fn bad_cursor() -> AppError {
    AppError::Validation("Invalid page cursor.".into())
}

/// Cursor for a `(created_at DESC, id DESC)` list. The timestamp is re-parsed so
/// a tampered key can never reach the database as invalid timestamp text.
pub fn decode_timestamp_key(
    cursor: Option<&str>,
    fingerprint: &str,
) -> Result<Option<(DateTime<Utc>, String)>, AppError> {
    let Some(cursor) = present(cursor) else {
        return Ok(None);
    };
    let (key, id) = decode(cursor, fingerprint)?;
    let stamp = DateTime::parse_from_rfc3339(&key)
        .map_err(|_| bad_cursor())?
        .with_timezone(&Utc);
    Ok(Some((stamp, id)))
}

/// Cursor for a `(created_at DESC, id DESC)` list whose row identity is a UUID.
pub fn decode_timestamp_uuid(
    cursor: Option<&str>,
    fingerprint: &str,
) -> Result<Option<(DateTime<Utc>, Uuid)>, AppError> {
    match decode_timestamp_key(cursor, fingerprint)? {
        Some((stamp, id)) => Ok(Some((
            stamp,
            Uuid::parse_str(&id).map_err(|_| bad_cursor())?,
        ))),
        None => Ok(None),
    }
}

/// Cursor for a `(created_at DESC, id DESC)` list whose row identity is a
/// BIGSERIAL (`admin_audit`, `admin_notes`).
pub fn decode_timestamp_i64(
    cursor: Option<&str>,
    fingerprint: &str,
) -> Result<Option<(DateTime<Utc>, i64)>, AppError> {
    match decode_timestamp_key(cursor, fingerprint)? {
        Some((stamp, id)) => Ok(Some((stamp, id.parse::<i64>().map_err(|_| bad_cursor())?))),
        None => Ok(None),
    }
}

/// Cursor for a text-keyed list (usernames) whose row identity is a UUID.
pub fn decode_text_key_uuid(
    cursor: Option<&str>,
    fingerprint: &str,
) -> Result<Option<(String, Uuid)>, AppError> {
    let Some(cursor) = present(cursor) else {
        return Ok(None);
    };
    let (key, id) = decode(cursor, fingerprint)?;
    Ok(Some((key, Uuid::parse_str(&id).map_err(|_| bad_cursor())?)))
}

/// One page of a list endpoint.
pub struct Page {
    pub items: Vec<Value>,
    pub next_cursor: Option<String>,
    pub has_more: bool,
}

impl Page {
    pub fn into_json(self) -> Value {
        serde_json::json!({
            "items": self.items,
            "next_cursor": self.next_cursor,
            "has_more": self.has_more,
        })
    }
}

/// Splits an over-fetched result set (`limit + 1` rows) into a page plus the
/// cursor for the following page.
///
/// `key` must return the ordering key and stable row id of a row; it is only
/// consulted for the last kept row, and only when another page exists.
pub fn finish(
    mut rows: Vec<Value>,
    limit: i64,
    fingerprint: &str,
    key: impl Fn(&Value) -> Option<(String, String)>,
) -> Page {
    let has_more = rows.len() as i64 > limit;
    if has_more {
        rows.truncate(limit as usize);
    }
    let next_cursor = if has_more {
        rows.last()
            .and_then(|row| key(row))
            .map(|(k, i)| encode(&k, &i, fingerprint))
    } else {
        None
    };
    Page {
        items: rows,
        next_cursor,
        has_more,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(key: &str, id: &str) -> Value {
        serde_json::json!({ "k": key, "i": id })
    }

    #[test]
    fn cursor_round_trips_for_the_same_filter_set() {
        let print = fingerprint(&["reports", "open", ""]);
        let cursor = encode("2026-09-25T10:11:12Z", "abc", &print);
        assert_eq!(
            decode(&cursor, &print).unwrap(),
            ("2026-09-25T10:11:12Z".to_string(), "abc".to_string())
        );
    }

    #[test]
    fn cursor_from_another_filter_set_is_rejected() {
        let cursor = encode("k", "i", &fingerprint(&["reports", "open"]));
        assert!(decode(&cursor, &fingerprint(&["reports", "resolved"])).is_err());
        assert!(decode(&cursor, &fingerprint(&["reports", "open", "extra"])).is_err());
    }

    #[test]
    fn malformed_and_oversized_cursors_are_rejected() {
        let print = fingerprint(&["audit"]);
        for bad in ["", "not base64!!", "YWJj", &"A".repeat(MAX_CURSOR_LEN + 1)] {
            assert!(decode(bad, &print).is_err(), "accepted {bad:?}");
        }
        // Valid base64, valid JSON, but unknown extra fields are refused.
        let extra = URL_SAFE_NO_PAD.encode(br#"{"k":"a","i":"b","f":"x","z":1}"#);
        assert!(decode(&extra, &print).is_err());
        // Empty key or id is refused even with a matching fingerprint.
        let empty_key = encode("", "b", &print);
        assert!(decode(&empty_key, &print).is_err());
        let empty_id = encode("a", "", &print);
        assert!(decode(&empty_id, &print).is_err());
    }

    #[test]
    fn control_characters_in_a_cursor_are_rejected() {
        let print = fingerprint(&["audit"]);
        assert!(decode(&encode("a\u{0}b", "c", &print), &print).is_err());
        assert!(decode(&encode("a", "b\ncc", &print), &print).is_err());
    }

    #[test]
    fn fingerprints_separate_length_packed_parts() {
        assert_ne!(fingerprint(&["ab", "c"]), fingerprint(&["a", "bc"]));
        assert_eq!(fingerprint(&["ab", "c"]), fingerprint(&["ab", "c"]));
    }

    #[test]
    fn page_size_is_bounded() {
        assert_eq!(limit(None).unwrap(), DEFAULT_LIMIT);
        assert_eq!(limit(Some(1)).unwrap(), 1);
        assert_eq!(limit(Some(MAX_LIMIT)).unwrap(), MAX_LIMIT);
        assert!(limit(Some(0)).is_err());
        assert!(limit(Some(-1)).is_err());
        assert!(limit(Some(MAX_LIMIT + 1)).is_err());
    }

    #[test]
    fn filters_are_whitelisted() {
        assert_eq!(choice(None, &["open"], "status").unwrap(), None);
        assert_eq!(choice(Some(""), &["open"], "status").unwrap(), None);
        assert_eq!(
            choice(Some(" open "), &["open"], "status").unwrap(),
            Some("open")
        );
        assert!(choice(Some("deleted"), &["open"], "status").is_err());
        assert_eq!(text(None, 10, "actor").unwrap(), None);
        assert_eq!(text(Some("abc"), 3, "actor").unwrap(), Some("abc"));
        assert!(text(Some("abcd"), 3, "actor").is_err());
        assert!(uuid(Some("nope")).is_err());
        assert_eq!(uuid(Some("")).unwrap(), None);
        assert!(instant(Some("yesterday")).is_err());
        assert_eq!(instant(Some("")).unwrap(), None);
        assert_eq!(
            instant(Some("2026-09-25T10:00:00+03:00"))
                .unwrap()
                .unwrap()
                .to_rfc3339(),
            "2026-09-25T07:00:00+00:00"
        );
    }

    #[test]
    fn finish_trims_the_probe_row_and_only_offers_a_cursor_when_more_exist() {
        let print = fingerprint(&["reports"]);
        let key = |v: &Value| {
            Some((
                v["k"].as_str().unwrap_or_default().to_string(),
                v["i"].as_str().unwrap_or_default().to_string(),
            ))
        };
        let page = finish(
            vec![row("a", "1"), row("b", "2"), row("c", "3")],
            2,
            &print,
            key,
        );
        assert_eq!(page.items.len(), 2);
        assert!(page.has_more);
        assert_eq!(
            decode(page.next_cursor.as_deref().unwrap(), &print).unwrap(),
            ("b".to_string(), "2".to_string())
        );

        let last = finish(vec![row("a", "1"), row("b", "2")], 2, &print, key);
        assert_eq!(last.items.len(), 2);
        assert!(!last.has_more);
        assert!(last.next_cursor.is_none());

        let empty = finish(vec![], 2, &print, key);
        assert!(empty.items.is_empty());
        assert!(!empty.has_more);
        assert!(empty.next_cursor.is_none());
    }

    #[test]
    fn typed_cursors_reject_a_tampered_key_before_it_reaches_sql() {
        let print = fingerprint(&["reports"]);
        assert!(decode_timestamp_key(None, &print).unwrap().is_none());
        assert!(decode_timestamp_key(Some("   "), &print).unwrap().is_none());

        let good = encode(
            "2026-09-25T10:11:12.000000Z",
            "11111111-1111-1111-1111-111111111111",
            &print,
        );
        let (stamp, id) = decode_timestamp_key(Some(&good), &print).unwrap().unwrap();
        assert_eq!(stamp.to_rfc3339(), "2026-09-25T10:11:12+00:00");
        assert_eq!(id, "11111111-1111-1111-1111-111111111111");

        // A well-formed cursor whose key is not a timestamp is refused here and
        // never becomes a database error.
        let bad_time = encode(
            "not-a-timestamp",
            "11111111-1111-1111-1111-111111111111",
            &print,
        );
        assert!(decode_timestamp_key(Some(&bad_time), &print).is_err());

        // Typed row identity is validated too.
        let bad_id = encode("2026-09-25T10:11:12Z", "nope", &print);
        assert!(decode_text_key_uuid(Some(&bad_id), &print).is_err());
        let ok_id = encode("username", "11111111-1111-1111-1111-111111111111", &print);
        assert_eq!(
            decode_text_key_uuid(Some(&ok_id), &print).unwrap().unwrap(),
            (
                "username".to_string(),
                Uuid::parse_str("11111111-1111-1111-1111-111111111111").unwrap()
            )
        );
        // A cursor from another filter set never reaches either parser.
        assert!(decode_text_key_uuid(Some(&ok_id), &fingerprint(&["users", "x"])).is_err());
    }
}

use std::collections::BTreeMap;
use std::fmt;

use serde::{Deserialize, Serialize};

/// Android-side notification importance/urgency is conveyed per-event; FCM
/// Message priority is a transport-level knob: user-visible chat and call
/// alerts need high priority to wake devices in Doze.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PushPriority {
    High,
    Normal,
}

/// A wake hint, never the source of truth (Этап K.4). Only routing metadata -
/// NEVER SDP, ICE candidates, Signal keys, TURN credentials, access tokens,
/// attachment keys, or plaintext message content.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PushEvent {
    pub event: String,
    #[serde(default)]
    pub data: BTreeMap<String, String>,
    #[serde(default = "default_priority")]
    pub priority: PushPriority,
    /// Seconds until FCM gives up delivering. Calls use a short TTL matched to
    /// the server ringing timeout; messages may live longer because `/sync` is
    /// always the correctness path.
    #[serde(default = "default_ttl")]
    pub ttl_seconds: u32,
    #[serde(default)]
    pub collapse_key: Option<String>,
}

fn default_priority() -> PushPriority {
    PushPriority::Normal
}

fn default_ttl() -> u32 {
    3600
}

impl PushEvent {
    pub fn incoming_call(call_id: uuid::Uuid, media_kind: &str) -> Self {
        PushEvent {
            event: "incoming_call".into(),
            data: BTreeMap::from([
                ("call_id".into(), call_id.to_string()),
                ("media_kind".into(), media_kind.to_string()),
            ]),
            priority: PushPriority::High,
            // Matched to the server ringing timeout (40s, Этап J) - never
            // deliver an incoming-call wake minutes after the call is dead.
            ttl_seconds: 60,
            // No collapse key: two different active calls must never replace
            // one another inside FCM's collapse bucket (Этап K.8).
            collapse_key: None,
        }
    }

    pub fn call_ended(call_id: uuid::Uuid, reason: &str) -> Self {
        PushEvent {
            event: "call_ended".into(),
            data: BTreeMap::from([
                ("call_id".into(), call_id.to_string()),
                ("reason".into(), reason.to_string()),
            ]),
            priority: PushPriority::High,
            ttl_seconds: 60,
            collapse_key: None,
        }
    }

    pub fn new_message(chat_id: uuid::Uuid, message_id: uuid::Uuid, sequence: i64) -> Self {
        PushEvent {
            event: "new_message".into(),
            data: BTreeMap::from([
                ("chat_id".into(), chat_id.to_string()),
                ("message_id".into(), message_id.to_string()),
                ("sequence".into(), sequence.to_string()),
            ]),
            priority: PushPriority::High,
            ttl_seconds: 86400,
            // Per-chat collapse: many queued messages in one chat coalesce to
            // one wake; the client's `/sync` is the correctness path.
            collapse_key: Some(format!("chat:{chat_id}")),
        }
    }

    /// Privacy-safe security alert (Этап L.18). NEVER carries a username,
    /// token, key, or any secret - only an opaque event id + the device's
    /// display name, which the recipient already knows about. The client must
    /// reconcile against `GET /security/events`; this is only a wake hint.
    pub fn security_event(
        event_id: i64,
        event_type: &str,
        subject_device_id: uuid::Uuid,
        subject_device_name: &str,
    ) -> Self {
        PushEvent {
            event: "security_event".into(),
            data: BTreeMap::from([
                ("security_event_id".into(), event_id.to_string()),
                ("event_type".into(), event_type.to_string()),
                ("subject_device_id".into(), subject_device_id.to_string()),
                (
                    "subject_device_name".into(),
                    subject_device_name.to_string(),
                ),
            ]),
            priority: PushPriority::High,
            ttl_seconds: 86400,
            collapse_key: None,
        }
    }

    /// Explicit operator-to-user notice. Unlike message pushes this content is
    /// intentionally user-visible, bounded by the admin API and persisted in
    /// `admin_user_notices` before it is queued.
    pub fn admin_notice(notice_id: uuid::Uuid, title: &str, body: &str, severity: &str) -> Self {
        PushEvent {
            event: "admin_notice".into(),
            data: BTreeMap::from([
                ("notice_id".into(), notice_id.to_string()),
                ("title".into(), title.to_string()),
                ("body".into(), body.to_string()),
                ("severity".into(), severity.to_string()),
            ]),
            priority: PushPriority::High,
            ttl_seconds: 86400,
            collapse_key: Some(format!("admin-notice:{notice_id}")),
        }
    }
}

/// Result of one FCM send attempt, classified so the outbox worker can decide
/// between retry (transient), token invalidation (UNREGISTERED), and terminal
/// failure.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PushSendOutcome {
    Delivered,
    /// FCM reported the registration token is no longer valid - the token
    /// must be invalidated and never retried.
    InvalidToken,
    /// Transient (429/5xx) - retry with bounded backoff.
    Transient,
    /// Permanent (bad payload / permission denied) - no retry.
    Permanent,
}

/// Sends one push event to one FCM registration token. Production
/// implementation is FCM HTTP v1; tests inject a fake so no test ever talks
/// to Google.
#[async_trait::async_trait]
pub trait PushProvider: Send + Sync {
    async fn send(&self, token: &str, event: &PushEvent) -> Result<PushSendOutcome, PushError>;
}

/// Errors surfaced by the push pipeline. Deliberately free of credential
/// material: message strings never contain the service-account JSON, the
/// private key, or the full FCM token.
#[derive(Debug)]
pub enum PushError {
    NotConfigured,
    Auth(String),
    Http(u16),
    Invalid(String),
    Serialization(String),
}

impl fmt::Display for PushError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PushError::NotConfigured => write!(f, "FCM is not configured"),
            PushError::Auth(msg) => write!(f, "FCM auth failed: {msg}"),
            PushError::Http(status) => write!(f, "FCM HTTP status {status}"),
            PushError::Invalid(msg) => write!(f, "invalid FCM configuration: {msg}"),
            PushError::Serialization(msg) => write!(f, "push serialization error: {msg}"),
        }
    }
}

impl std::error::Error for PushError {}

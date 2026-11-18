use base64::{engine::general_purpose::STANDARD, Engine as _};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// One Signal Protocol ciphertext blob addressed to a single recipient
/// device. Sessions (and therefore ciphertext) are per-device, so sending a
/// message to a chat means encrypting once per active device of every other
/// member (plus, optionally, the sender's own other devices for
/// multi-device sync) - the client decides the recipient list, the server
/// just validates and relays.
#[derive(Debug, Deserialize)]
pub struct EnvelopeInput {
    pub recipient_device_id: Uuid,
    /// 1 = PreKeySignalMessage (establishes/re-establishes a session), 2 = SignalMessage.
    pub message_type: i16,
    pub ciphertext: String,
}

#[derive(Debug, Deserialize)]
pub struct SendMessageRequest {
    pub client_message_id: Uuid,
    pub envelopes: Vec<EnvelopeInput>,
    /// Own devices deliberately withheld by a client pending local identity approval.
    #[serde(default)]
    pub withheld_device_ids: Vec<Uuid>,
    /// Attachments (см. Этап H) this message references - storage
    /// authorization references only. Crypto metadata (key/nonce/filename)
    /// never appears here; it's inside the per-device Signal ciphertext.
    #[serde(default)]
    pub attachment_ids: Vec<Uuid>,
    /// Этап I: 'content' (default) | 'edit' | 'delete' | 'reaction'. Purely
    /// a server-visible routing/validation discriminator - the actual
    /// edited text/reaction emoji/reply quote never appears here, only
    /// inside the encrypted envelopes above.
    #[serde(default = "default_kind")]
    pub kind: String,
    /// Dual-purpose by `kind`: reply-to reference for 'content', required
    /// lifecycle target for 'edit'/'delete'/'reaction'.
    #[serde(default)]
    pub target_message_id: Option<Uuid>,
    /// Album id of a content message: only the first message of a batch alerts
    /// each recipient device audibly; the rest get a quiet APNs alert (no sound)
    /// so each item is still acknowledged as delivered.
    #[serde(default)]
    pub notification_batch_id: Option<Uuid>,
}

fn default_kind() -> String {
    "content".to_string()
}

impl SendMessageRequest {
    /// Explicit omissions never grant authority over a peer/current device.
    pub fn validate_own_device_coverage(&self, own_devices: &[(Uuid, bool)], recipients: &[Uuid]) -> Result<(), crate::error::AppError> {
        if self.withheld_device_ids.iter().any(|id| !own_devices.iter().any(|(own, _)| own == id)) {
            return Err(crate::error::AppError::Validation("Withheld devices must be active own devices.".into()));
        }
        for (device, blocked) in own_devices {
            if !blocked && !recipients.contains(device) && !self.withheld_device_ids.contains(device) {
                return Err(crate::error::AppError::Validation("Every other active device of the sender must receive an envelope.".into()));
            }
        }
        Ok(())
    }
    /// Check parsed identities before any request can acquire database locks.
    pub fn validate_attachment_ids(&self) -> Result<(), crate::error::AppError> {
        if self.withheld_device_ids.len() > 127 {
            return Err(crate::error::AppError::Validation("At most 127 withheld devices.".into()));
        }
        let withheld: std::collections::HashSet<_> = self.withheld_device_ids.iter().collect();
        if withheld.len() != self.withheld_device_ids.len() || self.envelopes.iter().any(|e| withheld.contains(&e.recipient_device_id)) {
            return Err(crate::error::AppError::Validation("Withheld devices must be distinct and cannot receive envelopes.".into()));
        }
        if self.attachment_ids.len() > 10 {
            return Err(crate::error::AppError::Validation("A message may reference at most 10 attachments.".into()));
        }
        let mut unique = std::collections::HashSet::with_capacity(self.attachment_ids.len());
        if self.attachment_ids.iter().any(|id| !unique.insert(*id)) {
            return Err(crate::error::AppError::Validation("Attachment IDs must be distinct.".into()));
        }
        if let Some(batch) = self.notification_batch_id {
            if self.kind != "content" || batch.is_nil() {
                return Err(crate::error::AppError::Validation("notification_batch_id requires a non-zero UUID on content messages.".into()));
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod attachment_reference_tests {
    use super::*;
    fn request(ids: Vec<Uuid>) -> SendMessageRequest {
        SendMessageRequest { client_message_id: Uuid::new_v4(), envelopes: vec![], withheld_device_ids: vec![], attachment_ids: ids, kind: default_kind(), target_message_id: None, notification_batch_id: None }
    }
    #[test]
    fn withheld_devices_are_explicit_bounded_distinct_and_disjoint() {
        let id = Uuid::new_v4();
        let mut req = request(vec![]);
        req.withheld_device_ids = vec![id];
        assert!(req.validate_attachment_ids().is_ok());
        req.withheld_device_ids = vec![id, id];
        assert!(req.validate_attachment_ids().is_err());
        req.withheld_device_ids = vec![id];
        req.envelopes.push(EnvelopeInput { recipient_device_id: id, message_type: 1, ciphertext: "YQ==".into() });
        assert!(req.validate_attachment_ids().is_err());
        req.envelopes.clear(); req.withheld_device_ids = (0..128).map(|_| Uuid::new_v4()).collect();
        assert!(req.validate_attachment_ids().is_err());
    }
    #[test]
    fn own_coverage_preserves_legacy_checks_and_admits_explicit_held_devices() {
        let a = Uuid::new_v4(); let b = Uuid::new_v4(); let blocked = Uuid::new_v4(); let foreign = Uuid::new_v4();
        let devices = [(a, false), (b, false), (blocked, true)];
        let mut req = request(vec![]);
        assert!(req.validate_own_device_coverage(&devices, &[a]).is_err());
        req.withheld_device_ids = vec![b];
        assert!(req.validate_own_device_coverage(&devices, &[a]).is_ok());
        req.withheld_device_ids = vec![blocked];
        assert!(req.validate_own_device_coverage(&devices, &[a, b]).is_ok());
        req.withheld_device_ids = vec![foreign];
        assert!(req.validate_own_device_coverage(&devices, &[a, b]).is_err());
    }
    #[test]
    fn attachment_references_are_bounded_distinct_and_allow_plain_text() {
        assert!(request(vec![]).validate_attachment_ids().is_ok());
        assert!(request((0..10).map(|_| Uuid::new_v4()).collect()).validate_attachment_ids().is_ok());
        assert!(request((0..11).map(|_| Uuid::new_v4()).collect()).validate_attachment_ids().is_err());
        let id=Uuid::new_v4();
        assert!(request(vec![id; 50_000]).validate_attachment_ids().is_err());
        assert!(request(vec![id,Uuid::parse_str(&id.simple().to_string().to_uppercase()).unwrap()]).validate_attachment_ids().is_err());
    }
}

/// The server never sees plaintext - only opaque ciphertext bytes. `text`
/// from the pre-E2E API is gone entirely; this is the encrypted payload
/// addressed to whichever device is asking (the requester's own
/// `auth.device_id`), computed server-side per request/push.
/// `sender_device_id` is required by the recipient to pick the right Signal
/// Protocol session (sessions are per sender-device, not per sender-user -
/// a sender with multiple devices has an independent session per device).
#[derive(Debug, Clone, Serialize)]
pub struct MessageDto {
    pub id: Uuid,
    pub chat_id: Uuid,
    pub client_message_id: Uuid,
    pub sender_id: Uuid,
    pub sender_device_id: Uuid,
    pub sequence: i64,
    pub created_at: DateTime<Utc>,
    pub message_type: i16,
    pub ciphertext: String,
    /// Этап I: 'content' | 'edit' | 'delete' | 'reaction' (server-visible).
    pub kind: String,
    pub target_message_id: Option<Uuid>,
    /// `Some(false)`: a later message of a notification batch (no alert/banner).
    pub notification_batch_alert: Option<bool>,
}

#[derive(Debug, Serialize)]
pub struct MessageResponse {
    pub id: Uuid,
    pub chat_id: Uuid,
    pub client_message_id: Uuid,
    pub sender_id: Uuid,
    pub sequence: i64,
    pub created_at: DateTime<Utc>,
    pub status: &'static str,
}

#[derive(Debug, Deserialize)]
pub struct ListMessagesQuery {
    pub before_sequence: Option<i64>,
    pub limit: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct MessageListResponse {
    pub messages: Vec<MessageDto>,
    pub has_more: bool,
    pub next_before_sequence: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct ReadReceiptRequest {
    pub sequence: i64,
}

#[derive(Debug, Serialize)]
pub struct ReadReceiptResponse {
    /// Effective read watermark after this call - equal to the request's
    /// `sequence` unless the request tried to move it backwards, in which
    /// case this reflects the (higher) watermark that was already recorded.
    pub sequence: i64,
}

/// Shared row shape for "an envelope addressed to the requester's own
/// device, joined with its logical message" - used by both history
/// pagination (`GET /chats/{id}/messages`) and global device-scoped sync
/// (`GET /sync`), which differ only in their `WHERE`/`ORDER BY` clauses.
#[derive(Debug, sqlx::FromRow)]
pub struct EnvelopeRow {
    pub id: Uuid,
    pub chat_id: Uuid,
    pub sender_id: Uuid,
    pub sender_device_id: Uuid,
    pub client_message_id: Uuid,
    pub sequence: i64,
    pub created_at: DateTime<Utc>,
    pub message_type: i16,
    pub ciphertext: Vec<u8>,
    pub kind: String,
    pub target_message_id: Option<Uuid>,
    pub notification_batch_alert: Option<bool>,
}

impl From<EnvelopeRow> for MessageDto {
    fn from(row: EnvelopeRow) -> Self {
        MessageDto {
            id: row.id,
            chat_id: row.chat_id,
            client_message_id: row.client_message_id,
            sender_id: row.sender_id,
            sender_device_id: row.sender_device_id,
            sequence: row.sequence,
            created_at: row.created_at,
            message_type: row.message_type,
            ciphertext: STANDARD.encode(row.ciphertext),
            kind: row.kind,
            target_message_id: row.target_message_id,
            notification_batch_alert: row.notification_batch_alert,
        }
    }
}

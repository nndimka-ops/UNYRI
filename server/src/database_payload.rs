//! Bounded inline payloads and transactional O(1) cumulative DB reservations.
//! Mutate reservations LAST: callers finish all user/device FK/fanout writes
//! before acquiring the global accounting lock. No global table SUM on sends.
use crate::error::AppError;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

pub const MAX_CIPHERTEXT_BYTES: usize = 1024 * 1024;
pub const MAX_PUBLIC_KEY_BYTES: usize = 1024;
pub const MAX_KYBER_KEY_BYTES: usize = 16 * 1024;
pub const MAX_SIGNATURE_BYTES: usize = 1024;
pub const MAX_UNUSED_PREKEYS: i64 = 1000;

pub fn decode_bounded(field: &str, value: &str, max_bytes: usize) -> Result<Vec<u8>, AppError> {
    if value.len() > max_bytes.div_ceil(3) * 4 {
        return Err(AppError::Validation(format!(
            "{field} exceeds its byte limit."
        )));
    }
    let bytes = STANDARD
        .decode(value)
        .map_err(|_| AppError::Validation(format!("Invalid base64 for {field}.")))?;
    if bytes.len() > max_bytes {
        return Err(AppError::Validation(format!(
            "{field} exceeds its byte limit."
        )));
    }
    Ok(bytes)
}

pub async fn reserve_message(
    tx: &mut Transaction<'_, Postgres>,
    message: Uuid,
    owner: Uuid,
    bytes: i64,
) -> Result<(), AppError> {
    sqlx::query("INSERT INTO message_payload_reservations(message_id,owner_id,bytes) VALUES($1,$2,$3) ON CONFLICT(message_id) DO NOTHING")
        .bind(message).bind(owner).bind(bytes).execute(&mut **tx).await?;
    Ok(())
}

pub async fn reserve_device_keys(
    tx: &mut Transaction<'_, Postgres>,
    device: Uuid,
    owner: Uuid,
) -> Result<(), AppError> {
    // Every subquery is device-indexed and the OTP inventory is bounded.
    let bytes: i64 = sqlx::query_scalar("SELECT 512::bigint \
        + COALESCE((SELECT SUM(octet_length(identity_key)+128) FROM device_identity_keys WHERE device_id=$1),0) \
        + COALESCE((SELECT SUM(octet_length(public_key)+octet_length(signature)+128) FROM signed_prekeys WHERE device_id=$1),0) \
        + COALESCE((SELECT SUM(octet_length(public_key)+octet_length(signature)+128) FROM kyber_prekeys WHERE device_id=$1),0) \
        + COALESCE((SELECT SUM(octet_length(public_key)+128) FROM one_time_prekeys WHERE device_id=$1),0)")
        .bind(device).fetch_one(&mut **tx).await?;
    sqlx::query("INSERT INTO device_payload_reservations(device_id,owner_id,bytes) VALUES($1,$2,$3) ON CONFLICT(device_id) DO UPDATE SET bytes=EXCLUDED.bytes")
        .bind(device).bind(owner).bind(bytes).execute(&mut **tx).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn decoded_and_encoded_limits_include_padding_and_prekey_overhead() {
        for size in [0, 1, 32, 33, 64, 1569, 16 * 1024] {
            let data = vec![7; size];
            let encoded = STANDARD.encode(&data);
            assert_eq!(decode_bounded("key", &encoded, size).unwrap(), data);
            if size > 0 {
                assert!(decode_bounded("key", &encoded, size - 1).is_err());
            }
        }
        assert!(decode_bounded("key", "!!!!", 32).is_err());
        assert!(decode_bounded("key", &"A".repeat(2_000_000), MAX_PUBLIC_KEY_BYTES).is_err());
        let maximum = STANDARD.encode(vec![1; MAX_CIPHERTEXT_BYTES]);
        assert_eq!(
            decode_bounded("ciphertext", &maximum, MAX_CIPHERTEXT_BYTES)
                .unwrap()
                .len(),
            MAX_CIPHERTEXT_BYTES
        );
        assert!(decode_bounded(
            "ciphertext",
            &STANDARD.encode(vec![1; MAX_CIPHERTEXT_BYTES + 1]),
            MAX_CIPHERTEXT_BYTES
        )
        .is_err());
    }
}

#[cfg(test)]
#[path = "database_payload_tests.rs"]
mod integration_tests;

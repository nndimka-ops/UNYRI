use crate::{error::AppError, state::AppState};
use image::{ImageDecoder, ImageReader};
use std::io::Cursor;
use tokio::sync::Semaphore;

static PROCESSING: Semaphore = Semaphore::const_new(2);

#[derive(Default, serde::Deserialize)]
pub struct AvatarSize {
    pub size: Option<u32>,
}

/// Lazy generation also upgrades existing avatars. Only bounded, fixed sizes are accepted.
pub async fn rendition(
    state: &AppState,
    key: &str,
    owner: uuid::Uuid,
    size: Option<u32>,
) -> Result<String, AppError> {
    let Some(size) = size else {
        return Ok(key.to_owned());
    };
    if ![96, 256, 640].contains(&size) {
        return Err(AppError::Validation(
            "Avatar size must be 96, 256 or 640.".into(),
        ));
    }
    let target = format!(
        "avatar-renditions/{}/size-{size}.jpg",
        key.trim_start_matches("avatars/")
    );
    if state
        .s3
        .head_object(&target)
        .await
        .map_err(|_| AppError::Internal)?
        .is_some()
    {
        return Ok(target);
    }
    // Keep existing avatars available while the bounded processor is busy.
    let Ok(permit) = PROCESSING.try_acquire() else {
        return Ok(key.to_owned());
    };
    if state
        .s3
        .head_object(&target)
        .await
        .map_err(|_| AppError::Internal)?
        .is_some()
    {
        return Ok(target);
    }
    let bytes = state
        .s3
        .read_avatar(key)
        .await
        .map_err(|_| AppError::Internal)?;
    let (images, _permit) = tokio::task::spawn_blocking(move || (resize(&bytes), permit))
        .await
        .map_err(|_| AppError::Internal)?;
    let images =
        images.map_err(|_| AppError::Validation("Unsupported or invalid avatar image.".into()))?;
    if !super::avatar_uploads::reserve_renditions(state,owner,key,&images).await? {return Ok(key.to_owned());}
    for (edge, bytes) in images {
        let derived = format!(
            "avatar-renditions/{}/size-{edge}.jpg",
            key.trim_start_matches("avatars/")
        );
        let length=bytes.len() as i64;
        let mut write=state.db.begin().await?;
        let root_live:Option<String>=sqlx::query_scalar("SELECT a.object_key FROM avatar_uploads a JOIN users u ON u.id=a.user_id WHERE a.object_key=$1 AND a.user_id=$2 AND u.avatar_key=$1 AND u.deleted_at IS NULL AND NOT a.cleanup_pending AND a.upload_lease_until>now() FOR UPDATE OF a")
            .bind(key).bind(owner).fetch_optional(&mut *write).await?;
        if root_live.is_none() {return Ok(key.to_owned());}
        let child_live:Option<String>=sqlx::query_scalar("SELECT object_key FROM avatar_uploads WHERE object_key=$1 AND user_id=$2 AND expected_bytes=$3 AND NOT cleanup_pending AND upload_lease_until>now() FOR UPDATE")
            .bind(&derived).bind(owner).bind(length).fetch_optional(&mut *write).await?;
        if child_live.is_none() {return Ok(key.to_owned());}
        state
            .s3
            .write_avatar(&derived, bytes)
            .await
            .map_err(|_| AppError::Internal)?;
        sqlx::query("UPDATE avatar_uploads SET is_complete=true,reserved_bytes=$2 WHERE object_key=$1 AND expected_bytes=$2 AND NOT cleanup_pending")
            .bind(&derived).bind(length).execute(&mut *write).await?;
        write.commit().await?;
    }
    Ok(target)
}

fn decode(bytes: &[u8]) -> anyhow::Result<image::DynamicImage> {
    let mut reader = ImageReader::new(Cursor::new(bytes)).with_guessed_format()?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(4096);
    limits.max_image_height = Some(4096);
    limits.max_alloc = Some(64 * 1024 * 1024);
    reader.limits(limits);
    let mut decoder = reader.into_decoder()?;
    let orientation = decoder.orientation()?;
    let mut image = image::DynamicImage::from_decoder(decoder)?;
    image.apply_orientation(orientation);
    Ok(image)
}
fn resize(bytes: &[u8]) -> anyhow::Result<Vec<(u32, Vec<u8>)>> {
    let image=decode(bytes)?;
    let mut out = Vec::new();
    for size in [96, 256, 640] {
        let resized = image.thumbnail(size, size).to_rgb8();
        let mut bytes = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, 85)
            .encode_image(&resized)?;
        out.push((size, bytes));
    }
    Ok(out)
}

pub async fn validate_original(state:&AppState,key:&str,expected:Option<i64>) -> Result<(),AppError> {
    let permit=PROCESSING.try_acquire().map_err(|_|AppError::Unavailable("Avatar processing is busy. Retry shortly.".into()))?;
    let actual=state.s3.head_object(key).await.map_err(|_|AppError::Internal)?
        .ok_or_else(||AppError::NotFound("Avatar upload not found.".into()))?;
    if actual==0 || actual>super::avatar_uploads::MAX_AVATAR_BYTES as u64 || expected.is_some_and(|n|n as u64!=actual) {
        return Err(AppError::Validation("Avatar upload size does not match its reservation.".into()));
    }
    let bytes=state.s3.read_avatar(key).await.map_err(|_|AppError::Validation("Avatar image could not be read.".into()))?;
    if bytes.len() as u64!=actual {return Err(AppError::Validation("Avatar changed during validation.".into()));}
    tokio::task::spawn_blocking(move|| {let _permit=permit;decode(&bytes).map(|_|())})
        .await.map_err(|_|AppError::Internal)?.map_err(|_|AppError::Validation("Unsupported or invalid avatar image.".into()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sizes_are_bounded_and_decodable() {
        let original = image::RgbImage::new(800, 400);
        let mut bytes = Vec::new();
        image::codecs::jpeg::JpegEncoder::new(&mut bytes)
            .encode_image(&original)
            .unwrap();
        for (size, bytes) in resize(&bytes).unwrap() {
            let image = image::load_from_memory(&bytes).unwrap();
            assert_eq!(image.width(), size);
            assert_eq!(image.height(), size / 2);
        }
    }
    #[test]
    fn rejects_invalid_image() {
        assert!(resize(b"not an image").is_err());
    }
}

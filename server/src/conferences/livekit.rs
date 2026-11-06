use super::config::ConferenceConfig;
use chrono::{DateTime, Utc};
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use uuid::Uuid;

pub const TOKEN_TTL_SECONDS: i64 = 60;
pub fn room_name(id: Uuid) -> String {
    format!("coco-conference-{id}")
}
pub fn participant_identity(user: Uuid, device: Uuid, admission: Uuid) -> String {
    format!("{user}:{device}:{admission}")
}

#[derive(Serialize, Deserialize)]
pub struct Claims {
    pub iss: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sub: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub exp: i64,
    pub nbf: i64,
    #[serde(default)]
    pub iat: i64,
    pub video: Value,
}
fn jwt(
    config: &ConferenceConfig,
    sub: Option<String>,
    name: Option<String>,
    video: Value,
    expires: i64,
) -> Result<String, crate::error::AppError> {
    let now = Utc::now().timestamp();
    encode(
        &Header::new(Algorithm::HS256),
        &Claims {
            iss: config.api_key.clone(),
            sub,
            name,
            exp: expires,
            nbf: now - 5,
            iat: now,
            video,
        },
        &EncodingKey::from_secret(config.api_secret.as_bytes()),
    )
    .map_err(|_| crate::error::AppError::Internal)
}
pub fn participant_token(
    config: &ConferenceConfig,
    room: Uuid,
    user: Uuid,
    device: Uuid,
    admission: Uuid,
    name: &str,
) -> Result<(String, DateTime<Utc>), crate::error::AppError> {
    participant_token_with_audio_policy(config, room, user, device, admission, name, false)
}
pub fn participant_token_with_audio_policy(
    config: &ConferenceConfig,
    room: Uuid,
    user: Uuid,
    device: Uuid,
    admission: Uuid,
    name: &str,
    audio_blocked: bool,
) -> Result<(String, DateTime<Utc>), crate::error::AppError> {
    let expires = Utc::now() + chrono::Duration::seconds(TOKEN_TTL_SECONDS);
    let token = jwt(
        config,
        Some(participant_identity(user, device, admission)),
        Some(name.to_owned()),
        json!({"roomJoin":true,"room":room_name(room),"canPublish":true,"canSubscribe":true,"canPublishData":false,"canPublishSources":publish_sources(audio_blocked),"canUpdateOwnMetadata":false,"roomCreate":false,"roomAdmin":false,"roomList":false,"roomRecord":false,"hidden":false,"recorder":false,"agent":false,"ingressAdmin":false}),
        expires.timestamp(),
    )?;
    Ok((token, expires))
}

fn publish_sources(audio_blocked: bool) -> Value {
    if audio_blocked {
        json!(["camera"])
    } else {
        json!(["camera", "microphone"])
    }
}

/// Absence/empty source lists grant all sources in LiveKit, not none. Accept
/// only explicitly camera-only grants while the account's audio is blocked.
pub fn audio_is_restricted(video: &Value) -> bool {
    video.get("canPublish") == Some(&Value::Bool(false))
        || video
            .get("canPublishSources")
            .and_then(Value::as_array)
            .is_some_and(|sources| {
                !sources.is_empty()
                    && sources
                        .iter()
                        .all(|source| source.as_str() == Some("camera"))
            })
}

enum Reply {
    Value(Value),
    NotFound,
}

async fn request(
    config: &ConferenceConfig,
    method: &str,
    grant: Value,
    body: Value,
) -> Result<Reply, crate::error::AppError> {
    let token = jwt(config, None, None, grant, Utc::now().timestamp() + 30)?;
    // Reuse transport only; credentials remain in request-scoped headers.
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    let client = CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(5))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("fixed HTTP client settings")
    });
    let response = client
        .post(format!(
            "{}/twirp/livekit.RoomService/{method}",
            config.api_url
        ))
        .bearer_auth(token)
        .header("content-type", "application/json")
        .body(body.to_string())
        .send()
        .await
        .map_err(|_| {
            crate::error::AppError::Unavailable("Conference media service is unavailable.".into())
        })?;
    if response.status().is_success() {
        let content = response
            .bytes()
            .await
            .map_err(|_| crate::error::AppError::Internal)?;
        if content.is_empty() {
            return Ok(Reply::Value(json!({})));
        }
        if content.len() > 1024 * 1024 {
            return Err(crate::error::AppError::Internal);
        }
        return serde_json::from_slice(&content)
            .map(Reply::Value)
            .map_err(|_| crate::error::AppError::Internal);
    }
    if matches!(
        method,
        "DeleteRoom" | "RemoveParticipant" | "ListParticipants" | "UpdateParticipant"
    ) && response.status() == reqwest::StatusCode::NOT_FOUND
    {
        // LiveKit Twirp not_found, never a proxy's HTML 404.
        let content = response
            .bytes()
            .await
            .map_err(|_| crate::error::AppError::Internal)?;
        if content.len() < 4096
            && serde_json::from_slice::<Value>(&content)
                .ok()
                .and_then(|v| v.get("code").and_then(Value::as_str).map(str::to_owned))
                .as_deref()
                == Some("not_found")
        {
            return Ok(Reply::NotFound);
        }
    }
    Err(crate::error::AppError::Unavailable(
        "Conference media service rejected the operation.".into(),
    ))
}
pub async fn create_room(
    config: &ConferenceConfig,
    id: Uuid,
    capacity: i16,
) -> Result<(), crate::error::AppError> {
    request(config,"CreateRoom",json!({"roomCreate":true}),json!({"name":room_name(id),"max_participants":capacity,"empty_timeout":super::ROOM_TTL_SECONDS,"departure_timeout":super::ROOM_TTL_SECONDS})).await.map(|_|())
}
pub async fn delete_room(
    config: &ConferenceConfig,
    id: Uuid,
) -> Result<(), crate::error::AppError> {
    request(
        config,
        "DeleteRoom",
        json!({"roomCreate":true}),
        json!({"room":room_name(id)}),
    )
    .await
    .map(|_| ())
}
pub async fn remove_participant(
    config: &ConferenceConfig,
    id: Uuid,
    user: Uuid,
    device: Uuid,
    admission: Uuid,
) -> Result<(), crate::error::AppError> {
    remove_identity(config, id, &participant_identity(user, device, admission)).await
}
pub async fn remove_identity(
    config: &ConferenceConfig,
    id: Uuid,
    identity: &str,
) -> Result<(), crate::error::AppError> {
    request(
        config,
        "RemoveParticipant",
        json!({"roomAdmin":true,"room":room_name(id)}),
        json!({"room":room_name(id),"identity":identity}),
    )
    .await
    .map(|_| ())
}

pub async fn list_participants(
    config: &ConferenceConfig,
    id: Uuid,
) -> Result<Option<Vec<String>>, crate::error::AppError> {
    // None means an authoritative Twirp not_found response. A failed request,
    // proxy 404, or malformed success response never confirms room absence.
    let reply = request(
        config,
        "ListParticipants",
        json!({"roomAdmin":true,"room":room_name(id)}),
        json!({"room":room_name(id)}),
    )
    .await?;
    let Reply::Value(value) = reply else {
        return Ok(None);
    };
    let Some(participants) = value.get("participants") else {
        return Ok(Some(vec![]));
    };
    let participants = participants
        .as_array()
        .ok_or(crate::error::AppError::Internal)?;
    participants
        .iter()
        .map(|participant| {
            participant
                .get("identity")
                .and_then(Value::as_str)
                .map(str::to_owned)
                .ok_or(crate::error::AppError::Internal)
        })
        .collect::<Result<Vec<_>, _>>()
        .map(Some)
}

/// Permission changes remove a published microphone track and reject later
/// microphone publications. Restoring permission never invokes remote unmute.
pub async fn set_audio_policy(
    config: &ConferenceConfig,
    id: Uuid,
    identity: &str,
    blocked: bool,
) -> Result<(), crate::error::AppError> {
    request(
        config,
        "UpdateParticipant",
        json!({"roomAdmin":true,"room":room_name(id)}),
        json!({"room":room_name(id),"identity":identity,"permission":{
            "can_subscribe":true,"can_publish":true,"can_publish_data":false,
            "can_publish_sources":if blocked {vec!["CAMERA"]} else {vec!["CAMERA","MICROPHONE"]},
            "can_update_metadata":false,"hidden":false,"recorder":false,"agent":false
        }}),
    )
    .await
    .map(|_| ())
}

#[derive(Debug)]
pub struct ModerationParticipant {
    pub identity: String,
    pub audio_restricted: bool,
}
pub async fn moderation_participants(
    config: &ConferenceConfig,
    id: Uuid,
) -> Result<Option<Vec<ModerationParticipant>>, crate::error::AppError> {
    let reply = request(
        config,
        "ListParticipants",
        json!({"roomAdmin":true,"room":room_name(id)}),
        json!({"room":room_name(id)}),
    )
    .await?;
    let Reply::Value(value) = reply else {
        return Ok(None);
    };
    let Some(participants) = value.get("participants") else {
        return Ok(Some(vec![]));
    };
    participants
        .as_array()
        .ok_or(crate::error::AppError::Internal)?
        .iter()
        .map(|participant| {
            let identity = participant
                .get("identity")
                .and_then(Value::as_str)
                .ok_or(crate::error::AppError::Internal)?
                .to_owned();
            let permission = participant.get("permission");
            let audio_restricted = permission.is_some_and(|permission| {
                permission
                    .get("canPublish")
                    .or_else(|| permission.get("can_publish"))
                    == Some(&Value::Bool(false))
                    || permission
                        .get("canPublishSources")
                        .or_else(|| permission.get("can_publish_sources"))
                        .and_then(Value::as_array)
                        .is_some_and(|sources| {
                            !sources.is_empty()
                                && sources
                                    .iter()
                                    .all(|v| v.as_str() == Some("CAMERA") || v.as_i64() == Some(1))
                        })
            });
            Ok(ModerationParticipant {
                identity,
                audio_restricted,
            })
        })
        .collect::<Result<Vec<_>, _>>()
        .map(Some)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn blocked_audio_requires_explicit_nonempty_camera_only_permission() {
        for unsafe_grant in [
            json!({}),
            json!({"canPublish":true}),
            json!({"canPublishSources":[]}),
            json!({"canPublishSources":["camera","microphone"]}),
            json!({"canPublishSources":["camera","screen_share_audio"]}),
            json!({"canPublishSources":[null]}),
        ] {
            assert!(!audio_is_restricted(&unsafe_grant));
        }
        assert!(audio_is_restricted(&json!({"canPublish":false})));
        assert!(audio_is_restricted(
            &json!({"canPublishSources":["camera"]})
        ));
        assert_eq!(publish_sources(true), json!(["camera"]));
        assert_eq!(publish_sources(false), json!(["camera", "microphone"]));
    }
    #[test]
    fn tokens_have_one_identity_room_and_no_admin_recording_or_data_grants() {
        let config = ConferenceConfig {
            url: "wss://fixture.example".into(),
            api_url: "http://127.0.0.1:7880".into(),
            api_key: "fixture".into(),
            api_secret: "fixture-secret-at-least-32-bytes-long".into(),
        };
        let (room, user, device, admission) = (
            Uuid::new_v4(),
            Uuid::new_v4(),
            Uuid::new_v4(),
            Uuid::new_v4(),
        );
        let (token, _) = participant_token(
            &config,
            room,
            user,
            device,
            admission,
            "Authoritative display name",
        )
        .unwrap();
        let mut validation = jsonwebtoken::Validation::new(Algorithm::HS256);
        validation.set_issuer(&[config.api_key.as_str()]);
        let claims = jsonwebtoken::decode::<Claims>(
            &token,
            &jsonwebtoken::DecodingKey::from_secret(config.api_secret.as_bytes()),
            &validation,
        )
        .unwrap()
        .claims;
        assert_eq!(
            claims.sub.as_deref(),
            Some(participant_identity(user, device, admission).as_str())
        );
        assert_eq!(claims.video["room"], room_name(room));
        assert_eq!(claims.name.as_deref(), Some("Authoritative display name"));
        assert_eq!(
            claims.video["canPublishSources"],
            json!(["camera", "microphone"])
        );
        for grant in [
            "roomCreate",
            "roomAdmin",
            "roomList",
            "roomRecord",
            "recorder",
            "hidden",
            "agent",
            "ingressAdmin",
            "canPublishData",
            "canUpdateOwnMetadata",
        ] {
            assert_eq!(claims.video[grant], false, "{grant}");
        }
        assert_eq!(claims.exp - claims.iat, TOKEN_TTL_SECONDS);
    }
}

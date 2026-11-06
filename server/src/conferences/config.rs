use std::{env, fmt};

/// Deliberately independent of Config/AppState literal constructors. Secrets
/// are excluded from Debug and every configuration error excludes values.
#[derive(Clone)]
pub struct ConferenceConfig {
    pub url: String,
    pub api_url: String,
    pub api_key: String,
    pub api_secret: String,
}
impl fmt::Debug for ConferenceConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ConferenceConfig")
            .field("configured", &true)
            .finish()
    }
}
impl ConferenceConfig {
    pub fn from_env() -> anyhow::Result<Option<Self>> {
        let production = env::var("COCO_ENV")
            .map(|v| v == "production")
            .unwrap_or(!cfg!(debug_assertions));
        Self::parse(production, |key| env::var(key).ok())
    }
    fn parse(
        production: bool,
        get: impl Fn(&str) -> Option<String>,
    ) -> anyhow::Result<Option<Self>> {
        let keys = [
            "CONFERENCE_LIVEKIT_URL",
            "CONFERENCE_LIVEKIT_API_URL",
            "CONFERENCE_LIVEKIT_API_KEY",
            "CONFERENCE_LIVEKIT_API_SECRET",
        ];
        let values: Vec<String> = keys
            .iter()
            .map(|key| get(key).unwrap_or_default())
            .collect();
        if values.iter().all(|value| value.is_empty()) {
            return Ok(None);
        }
        for (key, value) in keys.iter().zip(&values) {
            anyhow::ensure!(
                !value.trim().is_empty(),
                "{key} is required when conferences are configured"
            );
        }
        let public = reqwest::Url::parse(&values[0])
            .map_err(|_| anyhow::anyhow!("CONFERENCE_LIVEKIT_URL is invalid"))?;
        let api = reqwest::Url::parse(&values[1])
            .map_err(|_| anyhow::anyhow!("CONFERENCE_LIVEKIT_API_URL is invalid"))?;
        for url in [&public, &api] {
            anyhow::ensure!(
                url.host_str().is_some()
                    && url.username().is_empty()
                    && url.password().is_none()
                    && url.query().is_none()
                    && url.fragment().is_none()
                    && url.path() == "/",
                "Conference endpoints must have a host and no credentials, path, query or fragment"
            );
        }
        anyhow::ensure!(
            public.scheme() == "wss" || (!production && public.scheme() == "ws"),
            "CONFERENCE_LIVEKIT_URL must use WSS in production"
        );
        let private_http = api.scheme() == "http"
            && api.host_str().is_some_and(|h| {
                h == "localhost"
                    || h.parse::<std::net::IpAddr>().is_ok_and(|ip| match ip {
                        std::net::IpAddr::V4(v) => v.is_loopback() || v.is_private(),
                        std::net::IpAddr::V6(v) => v.is_loopback() || v.is_unique_local(),
                    })
            });
        anyhow::ensure!(
            api.scheme() == "https" || private_http || (!production && api.scheme() == "http"),
            "CONFERENCE_LIVEKIT_API_URL requires HTTPS or a private literal IP in production"
        );
        anyhow::ensure!(
            values[3].len() >= 32,
            "CONFERENCE_LIVEKIT_API_SECRET must contain at least 32 bytes"
        );
        anyhow::ensure!(get("CONFERENCE_LIVEKIT_AUTOCREATE_DISABLED").as_deref()==Some("true"), "Conferences require CONFERENCE_LIVEKIT_AUTOCREATE_DISABLED=true and room.auto_create=false on the SFU");
        anyhow::ensure!(get("CONFERENCE_GATEWAY_REQUIRED").as_deref()==Some("true"), "Conferences require CONFERENCE_GATEWAY_REQUIRED=true and authorization on every public SFU signaling connection");
        Ok(Some(Self {
            url: values[0].trim_end_matches('/').into(),
            api_url: values[1].trim_end_matches('/').into(),
            api_key: values[2].clone(),
            api_secret: values[3].clone(),
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn configured(key: &str) -> Option<String> {
        Some(
            match key {
                "CONFERENCE_LIVEKIT_URL" => "wss://meet.example.com",
                "CONFERENCE_LIVEKIT_API_URL" => "http://127.0.0.1:7880",
                "CONFERENCE_LIVEKIT_API_KEY" => "test-api-key",
                "CONFERENCE_LIVEKIT_API_SECRET" => "fixture-secret-thirty-two-bytes-long",
                "CONFERENCE_LIVEKIT_AUTOCREATE_DISABLED" | "CONFERENCE_GATEWAY_REQUIRED" => "true",
                _ => return None,
            }
            .into(),
        )
    }
    #[test]
    fn disabled_without_credentials_and_partial_configuration_fails() {
        assert!(ConferenceConfig::parse(true, |_| None).unwrap().is_none());
        assert!(
            ConferenceConfig::parse(true, |k| if k == "CONFERENCE_LIVEKIT_API_SECRET" {
                None
            } else {
                configured(k)
            })
            .is_err()
        );
    }
    #[test]
    fn validates_public_tls_and_explicit_auto_create_safety() {
        assert!(ConferenceConfig::parse(true, configured).is_ok());
        for (key, value) in [
            ("CONFERENCE_LIVEKIT_URL", "ws://meet.example.com"),
            ("CONFERENCE_LIVEKIT_API_URL", "http://public.example.com"),
            ("CONFERENCE_LIVEKIT_AUTOCREATE_DISABLED", "false"),
            ("CONFERENCE_LIVEKIT_API_SECRET", "short"),
            (
                "CONFERENCE_LIVEKIT_URL",
                "wss://username:password@example.com",
            ),
        ] {
            assert!(
                ConferenceConfig::parse(true, |k| if k == key {
                    Some(value.into())
                } else {
                    configured(k)
                })
                .is_err(),
                "{key}"
            );
        }
    }
    #[test]
    fn debug_never_includes_credentials() {
        let config = ConferenceConfig::parse(true, configured).unwrap().unwrap();
        let debug = format!("{config:?}");
        assert!(!debug.contains(&config.api_key));
        assert!(!debug.contains(&config.api_secret));
    }
}

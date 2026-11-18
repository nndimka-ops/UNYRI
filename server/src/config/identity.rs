//! Deployment-local identity configuration. Never infer a public origin from
//! request headers: Host/Forwarded values are controlled by callers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IdentityQuota {
    pub user: u32,
    pub ip: u32,
}

#[derive(Debug, Clone)]
pub struct IdentityConfig {
    pub public_origin: String,
    pub lookup_minute: IdentityQuota,
    pub lookup_day: IdentityQuota,
    pub read: IdentityQuota,
    pub write: IdentityQuota,
    pub admin: IdentityQuota,
    pub avatar: IdentityQuota,
}

impl Default for IdentityConfig {
    fn default() -> Self {
        Self {
            public_origin: "http://localhost:8080".into(),
            lookup_minute: IdentityQuota { user: 30, ip: 120 },
            lookup_day: IdentityQuota {
                user: 300,
                ip: 3000,
            },
            read: IdentityQuota { user: 120, ip: 240 },
            write: IdentityQuota { user: 10, ip: 60 },
            admin: IdentityQuota { user: 60, ip: 120 },
            avatar: IdentityQuota { user: 120, ip: 240 },
        }
    }
}

impl IdentityConfig {
    pub(super) fn load(
        host: &str,
        port: u16,
        get: impl Fn(&str) -> Option<String>,
    ) -> anyhow::Result<Self> {
        let mut config = Self::default();
        // B-030: deployments already publish their HTTPS origin as PUBLIC_API_ORIGIN
        // (official and self-hosted compose files). Prefer it to the listener, which
        // behind a proxy is a loopback address nobody else can reach. An unusable
        // PUBLIC_API_ORIGIN is ignored here so this fallback never stops a boot.
        let api_origin = get("PUBLIC_API_ORIGIN").filter(|origin| canonical_origin(origin).is_ok());
        let raw_origin = get("PUBLIC_ORIGIN").or(api_origin).unwrap_or_else(|| {
            let host = match host {
                "0.0.0.0" | "::" | "[::]" => "localhost",
                host => host,
            };
            let host = if host.contains(':') && !host.starts_with('[') {
                format!("[{host}]")
            } else {
                host.to_owned()
            };
            format!("http://{host}:{port}")
        });
        config.public_origin = canonical_origin(&raw_origin)?;
        for (quota, operation, window) in [
            (&mut config.lookup_minute, "LOOKUP", "MINUTE"),
            (&mut config.lookup_day, "LOOKUP", "DAY"),
            (&mut config.read, "READ", "MINUTE"),
            (&mut config.write, "WRITE", "MINUTE"),
            (&mut config.admin, "ADMIN", "MINUTE"),
            (&mut config.avatar, "AVATAR", "MINUTE"),
        ] {
            for (limit, subject) in [(&mut quota.user, "USER"), (&mut quota.ip, "IP")] {
                let key = format!("IDENTITY_{operation}_{subject}_PER_{window}");
                if let Some(value) = get(&key) {
                    *limit = value
                        .parse::<u32>()
                        .ok()
                        .filter(|limit| *limit > 0)
                        .ok_or_else(|| {
                            anyhow::anyhow!("{key} must be an integer in 1..=4294967295")
                        })?;
                }
            }
        }
        Ok(config)
    }
}

fn canonical_origin(raw: &str) -> anyhow::Result<String> {
    // URL parsers can silently trim controls, decode authorities, or turn
    // backslashes/dot segments into paths. Reject those spellings up front.
    let invalid = || {
        anyhow::anyhow!("PUBLIC_ORIGIN must be an absolute HTTP(S) origin without credentials, path, query or fragment")
    };
    if raw.len() > 2048
        || raw.chars().any(|c| c.is_whitespace() || c.is_control())
        || raw.bytes().any(|b| b"\\%@?#".contains(&b))
    {
        return Err(invalid());
    }
    let (_, authority) = raw.split_once("://").ok_or_else(invalid)?;
    if authority.trim_end_matches('/').contains('/')
        || authority.ends_with("//")
        || authority.trim_end_matches('/').ends_with(':')
    {
        return Err(invalid());
    }
    let url = reqwest::Url::parse(raw).map_err(|_| invalid())?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || url.port() == Some(0)
        || url.path() != "/"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(invalid());
    }
    Ok(url.origin().ascii_serialization())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn origin(value: &str) -> anyhow::Result<IdentityConfig> {
        IdentityConfig::load("0.0.0.0", 8080, |key| {
            (key == "PUBLIC_ORIGIN").then(|| value.into())
        })
    }

    #[test]
    fn identity_public_origin_canonicalizes_self_hosted_origins() {
        for (raw, expected) in [
            ("https://Chat.Example:443/", "https://chat.example"),
            ("https://chat.example:8443", "https://chat.example:8443"),
            ("http://127.0.0.1:8080/", "http://127.0.0.1:8080"),
            ("https://[2001:db8::1]:8443", "https://[2001:db8::1]:8443"),
            ("https://пример.рф", "https://xn--e1afmkfd.xn--p1ai"),
        ] {
            assert_eq!(origin(raw).unwrap().public_origin, expected);
        }
    }

    #[test]
    fn identity_public_origin_rejects_ambiguous_or_credentialed_urls() {
        for raw in [
            "",
            "chat.example",
            "//chat.example",
            "ftp://chat.example",
            "https://",
            "https://chat.example:0",
            "https://chat.example:65536",
            "https://chat.example:",
            "https://chat.example:/",
            "https://user:secret@chat.example",
            "https://@chat.example",
            "https://chat.example/api",
            "https://chat.example/a/..",
            "https://chat.example//",
            "https://chat.example?token=secret",
            "https://chat.example#fragment",
            "https://chat.example\\evil",
            "https://chat%2eexample",
            " https://chat.example",
            "https://chat.example\n",
        ] {
            assert!(origin(raw).is_err(), "{raw:?}");
        }
    }

    #[test]
    fn identity_origin_falls_back_to_the_public_api_origin() {
        // B-030: production listens on 127.0.0.1 behind its proxy and only set PUBLIC_API_ORIGIN.
        let api = |key: &str| (key == "PUBLIC_API_ORIGIN").then(|| "https://Chat.Example/".to_owned());
        assert_eq!(IdentityConfig::load("127.0.0.1", 8080, api).unwrap().public_origin, "https://chat.example");
        let both = |key: &str| match key {
            "PUBLIC_ORIGIN" => Some("https://identity.example".to_owned()),
            "PUBLIC_API_ORIGIN" => Some("https://chat.example".to_owned()),
            _ => None,
        };
        assert_eq!(IdentityConfig::load("127.0.0.1", 8080, both).unwrap().public_origin, "https://identity.example");
        // A malformed API origin is ignored, not a boot failure; the listener remains the fallback.
        let broken = |key: &str| (key == "PUBLIC_API_ORIGIN").then(|| "https://chat.example/api".to_owned());
        assert_eq!(IdentityConfig::load("127.0.0.1", 8080, broken).unwrap().public_origin, "http://127.0.0.1:8080");
    }

    #[test]
    fn identity_origin_defaults_to_the_listener_without_a_special_domain() {
        for (host, expected) in [
            ("0.0.0.0", "http://localhost:8123"),
            ("::", "http://localhost:8123"),
            ("192.168.1.20", "http://192.168.1.20:8123"),
            ("2001:db8::1", "http://[2001:db8::1]:8123"),
        ] {
            assert_eq!(
                IdentityConfig::load(host, 8123, |_| None)
                    .unwrap()
                    .public_origin,
                expected
            );
        }
    }

    #[test]
    fn identity_quotas_keep_confirmed_defaults_and_accept_independent_overrides() {
        let defaults = IdentityConfig::load("localhost", 8080, |_| None).unwrap();
        assert_eq!(defaults.lookup_minute, IdentityQuota { user: 30, ip: 120 });
        assert_eq!(
            defaults.lookup_day,
            IdentityQuota {
                user: 300,
                ip: 3000
            }
        );
        assert_eq!(defaults.read, IdentityQuota { user: 120, ip: 240 });
        assert_eq!(defaults.write, IdentityQuota { user: 10, ip: 60 });
        assert_eq!(defaults.admin, IdentityQuota { user: 60, ip: 120 });
        assert_eq!(defaults.avatar, IdentityQuota { user: 120, ip: 240 });
        let custom = IdentityConfig::load("localhost", 8080, |key| {
            key.starts_with("IDENTITY_")
                .then(|| if key.contains("_USER_") { "2" } else { "7" }.into())
        })
        .unwrap();
        for quota in [
            custom.lookup_minute,
            custom.lookup_day,
            custom.read,
            custom.write,
            custom.admin,
            custom.avatar,
        ] {
            assert_eq!(quota, IdentityQuota { user: 2, ip: 7 });
        }
    }

    #[test]
    fn identity_quota_configuration_fails_closed_on_invalid_values() {
        for value in ["", "0", "-1", "1.5", " 10", "10 ", "4294967296", "off"] {
            assert!(
                IdentityConfig::load("localhost", 8080, |key| {
                    (key == "IDENTITY_WRITE_USER_PER_MINUTE").then(|| value.into())
                })
                .is_err(),
                "{value:?}"
            );
        }
    }
}

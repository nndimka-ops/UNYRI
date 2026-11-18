//! Owner/admin configuration snapshot only. No DNS lookup or network probes.
use serde_json::{json, Value};
use std::net::IpAddr;

fn value(get: &impl Fn(&str) -> Option<String>, key: &str) -> Option<String> {
    get(key)
        .map(|v| v.trim().to_owned())
        .filter(|v| !v.is_empty())
}

fn port(raw: &str) -> Option<u16> {
    (!raw.is_empty() && raw.bytes().all(|b| b.is_ascii_digit()))
        .then(|| raw.parse::<u16>().ok())
        .flatten()
        .filter(|p| *p > 0)
}

fn choice(get: &impl Fn(&str) -> Option<String>, key: &str, allowed: &[&str]) -> Option<String> {
    value(get, key).filter(|v| allowed.contains(&v.as_str()))
}

fn public_ip(raw: &str) -> Option<IpAddr> {
    raw.parse::<IpAddr>().ok().filter(|ip| match ip {
        IpAddr::V4(v) => {
            let o = v.octets();
            !v.is_private()
                && !v.is_loopback()
                && !v.is_link_local()
                && !v.is_multicast()
                && !v.is_broadcast()
                && o[0] != 0
                && o[0] < 240
                && !(o[0] == 100 && (64..=127).contains(&o[1]))
        }
        IpAddr::V6(v) => {
            !v.is_unspecified()
                && !v.is_loopback()
                && !v.is_unique_local()
                && !v.is_unicast_link_local()
                && !v.is_multicast()
                && v.to_ipv4().is_none()
        }
    })
}

fn in_subnet(ip: IpAddr, subnet: &str) -> bool {
    let Some((network, bits)) = subnet.split_once('/') else {
        return false;
    };
    match (ip, network.parse::<IpAddr>(), bits.parse::<u32>()) {
        (IpAddr::V4(ip), Ok(IpAddr::V4(net)), Ok(bits @ 1..=32)) => {
            let mask = u32::MAX << (32 - bits);
            u32::from(ip) & mask == u32::from(net) & mask
        }
        (IpAddr::V6(ip), Ok(IpAddr::V6(net)), Ok(bits @ 1..=128)) => {
            let mask = u128::MAX << (128 - bits);
            u128::from(ip) & mask == u128::from(net) & mask
        }
        _ => false,
    }
}

fn lan_ip(raw: &str, subnet: Option<&str>) -> Option<IpAddr> {
    raw.parse::<IpAddr>().ok().filter(|ip| {
        let private = match ip {
            IpAddr::V4(v) => v.is_private() && !v.is_broadcast(),
            IpAddr::V6(v) => v.is_unique_local(),
        };
        // Default Docker bridge and this installation's effective Compose network.
        // Other virtual/VPN interfaces are excluded by the host detector.
        private
            && !in_subnet(*ip, "172.17.0.0/16")
            && !in_subnet(*ip, subnet.unwrap_or("172.30.219.0/24"))
    })
}

fn origin(raw: &str) -> Option<reqwest::Url> {
    if raw.len() > 2048
        || raw.chars().any(|c| c.is_control() || c.is_whitespace())
        || raw.contains('\\')
    {
        return None;
    }
    let authority = raw.strip_prefix("https://")?;
    if authority.contains('@')
        || authority
            .split_once('/')
            .is_some_and(|(_, p)| !p.is_empty())
    {
        return None;
    }
    let url = reqwest::Url::parse(raw).ok()?;
    let host = url.host_str()?;
    let dns = host.len() <= 253
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-')
        });
    // IP literals are allowed as configured origins, but must be public unicast.
    let ip_host = host.trim_matches(|c| c == '[' || c == ']');
    let safe_host = if ip_host.parse::<IpAddr>().is_ok() {
        public_ip(ip_host).is_some()
    } else {
        dns && host != "localhost" && !host.ends_with(".localhost")
    };
    (url.scheme() == "https"
        && safe_host
        && url.username().is_empty()
        && url.password().is_none()
        && url.path() == "/"
        && url.query().is_none()
        && url.fragment().is_none()
        && url.port_or_known_default().is_some_and(|p| p > 0))
    .then_some(url)
}

fn turn_endpoint(raw: &str) -> Option<(u16, &'static str)> {
    let (scheme, rest) = raw.split_once(':')?;
    if !matches!(scheme, "turn" | "turns") || !super::sane_turn_url(raw) {
        return None;
    }
    let (authority, query) = rest.split_once('?').unwrap_or((rest, ""));
    let port = port(authority.rsplit_once(':')?.1)?;
    let transport = match query {
        "transport=udp" if scheme == "turn" => "udp",
        "transport=tcp" => "tcp",
        "" if scheme == "turn" => "udp",
        "" => "tcp",
        _ => return None,
    };
    Some((port, transport))
}

pub(super) fn snapshot(
    turn: &crate::config::TurnConfig,
    get: &impl Fn(&str) -> Option<String>,
    rooms_enabled: Option<bool>,
) -> Value {
    let public = value(get, "PUBLIC_API_ORIGIN").and_then(|v| origin(&v));
    let external_raw = value(get, "TURN_EXTERNAL_IP");
    let external = external_raw.as_deref().and_then(public_ip);
    let hosting = choice(get, "UNYRI_HOSTING_KIND", &["home_pc", "vps", "unknown"]);
    let lan_raw = value(get, "UNYRI_LAN_ADDRESS");
    let subnet = value(get, "UNYRI_SUBNET");
    let lan = lan_raw
        .as_deref()
        .and_then(|v| lan_ip(v, subnet.as_deref()));
    let port_raw = value(get, "UNYRI_LAN_PORT");
    let lan_port = port_raw.as_deref().and_then(port);
    let time_raw = value(get, "UNYRI_LAN_DETECTED_AT");
    let observed_at = time_raw
        .as_deref()
        .filter(|v| v.len() <= 64)
        .and_then(|v| chrono::DateTime::parse_from_rfc3339(v).ok())
        .map(|t| {
            t.with_timezone(&chrono::Utc)
                .to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
        });
    let lan_state = if hosting.as_deref() == Some("vps") {
        "not_applicable"
    } else if (lan_raw.is_some() && lan.is_none())
        || (port_raw.is_some() && lan_port.is_none())
        || (time_raw.is_some() && observed_at.is_none())
    {
        "invalid"
    } else if lan.is_some() && lan_port.is_some() && observed_at.is_some() {
        "detected"
    } else {
        "unknown"
    };
    let endpoints: Option<Vec<_>> = turn.turn_urls.iter().map(|s| turn_endpoint(s)).collect();
    let endpoints = endpoints.filter(|v| !v.is_empty() && v.iter().all(|e| e.0 == v[0].0));
    let turn_port = endpoints.as_ref().map(|v| v[0].0);
    let transports = endpoints.as_ref().map(|v| {
        ["udp", "tcp"]
            .into_iter()
            .filter(|t| v.iter().any(|e| e.1 == *t))
            .collect::<Vec<_>>()
    });
    let relay_min = value(get, "TURN_MIN_PORT").as_deref().and_then(port);
    let relay_max = value(get, "TURN_MAX_PORT").as_deref().and_then(port);
    let relay = relay_min.zip(relay_max).filter(|(a, b)| a <= b);
    let ingress = choice(get, "UNYRI_INGRESS_MODE", &["direct", "tunnel"]);
    let provider = if ingress.as_deref() == Some("tunnel") {
        choice(
            get,
            "UNYRI_TUNNEL_PROVIDER",
            &["tailscale_funnel", "cloudflare"],
        )
    } else {
        None
    };
    json!({
        "schema":1,
        "public_origin":public.as_ref().map(|u| u.origin().ascii_serialization()),
        "public_host":public.as_ref().and_then(|u| u.host_str()),
        "public_port":public.as_ref().and_then(|u| u.port_or_known_default()),
        "external_ip":{"address":external.map(|ip| ip.to_string()),
            "source":external.map(|_| "installer_config"),"observed_at":null,
            "state":if external.is_some() { "unverified" } else if external_raw.is_some() { "invalid" } else { "unknown" }},
        "lan":{"address":if lan_state == "not_applicable" { None } else { lan.map(|ip| ip.to_string()) },
            "port":if lan_state == "not_applicable" { None } else { lan_port },
            "source":lan.filter(|_| lan_state != "not_applicable").map(|_| "default_route"),
            "observed_at":if lan_state == "not_applicable" { None } else { observed_at },"state":lan_state},
        "calls":{"turn_port":turn_port,"transports":transports,
            "relay_min":relay.map(|r| r.0),"relay_max":relay.map(|r| r.1),
            "relay_protocol":choice(get, "TURN_RELAY_PROTOCOL", &["udp", "tcp"])},
        "rooms":{"enabled":rooms_enabled},"ingress":{"mode":ingress,"provider":provider},"hosting":hosting
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn check(values: &[(&str, &str)], urls: &[&str]) -> Value {
        let turn = crate::config::TurnConfig {
            stun_urls: vec![],
            turn_urls: urls.iter().map(|s| s.to_string()).collect(),
            shared_secret: "fixture-only-secret-never-serialized".into(),
            credential_ttl_seconds: 1200,
        };
        let result = snapshot(
            &turn,
            &|k| values.iter().find(|v| v.0 == k).map(|v| v.1.to_owned()),
            Some(false),
        );
        let serialized = serde_json::to_string(&result).unwrap();
        assert!(!serialized.contains(&turn.shared_secret));
        serde_json::from_str(&serialized).unwrap()
    }

    #[test]
    fn absent_values_are_null_not_defaults() {
        let v = check(&[], &[]);
        assert_eq!(
            v,
            json!({"schema":1,"public_origin":null,"public_host":null,"public_port":null,
            "external_ip":{"address":null,"source":null,"observed_at":null,"state":"unknown"},
            "lan":{"address":null,"port":null,"source":null,"observed_at":null,"state":"unknown"},
            "calls":{"turn_port":null,"transports":null,"relay_min":null,"relay_max":null,"relay_protocol":null},
            "rooms":{"enabled":false},"ingress":{"mode":null,"provider":null},"hosting":null})
        );
    }

    #[test]
    fn fixture_serializes_the_phase_one_contract() {
        let v = check(
            &[
                ("PUBLIC_API_ORIGIN", "https://HOME.example.test:443/"),
                ("TURN_EXTERNAL_IP", "203.0.113.8"),
                ("UNYRI_LAN_ADDRESS", "192.168.0.10"),
                ("UNYRI_LAN_PORT", "443"),
                ("UNYRI_LAN_DETECTED_AT", "2026-11-18T14:00:00+02:00"),
                ("TURN_MIN_PORT", "49152"),
                ("TURN_MAX_PORT", "49200"),
                ("TURN_RELAY_PROTOCOL", "udp"),
                ("UNYRI_INGRESS_MODE", "direct"),
                ("UNYRI_HOSTING_KIND", "home_pc"),
            ],
            &[
                "turn:home.example.test:3478?transport=udp",
                "turn:home.example.test:3478?transport=tcp",
            ],
        );
        assert_eq!(
            v,
            json!({"schema":1,"public_origin":"https://home.example.test",
            "public_host":"home.example.test","public_port":443,
            "external_ip":{"address":"203.0.113.8","source":"installer_config","observed_at":null,"state":"unverified"},
            "lan":{"address":"192.168.0.10","port":443,"source":"default_route","observed_at":"2026-11-18T12:00:00Z","state":"detected"},
            "calls":{"turn_port":3478,"transports":["udp","tcp"],"relay_min":49152,"relay_max":49200,"relay_protocol":"udp"},
            "rooms":{"enabled":false},"ingress":{"mode":"direct","provider":null},"hosting":"home_pc"})
        );
    }

    #[test]
    fn origins_require_https_authority_and_explicit_display_port() {
        for invalid in [
            "http://example.test",
            "https://example.test:0",
            "https://example.test:65536",
            "https://user:secret@example.test",
            "https://example.test/path",
            "https://example.test/a/..",
            "https://example.test/?secret=x",
            "https://example.test/#fragment",
            "https://localhost",
            "https://0.0.0.0",
            "https://172.30.219.10",
            "https://bad_name.test",
            "error from installer",
        ] {
            let v = check(&[("PUBLIC_API_ORIGIN", invalid)], &[]);
            assert!(v["public_origin"].is_null(), "{invalid}");
            assert!(v["public_host"].is_null());
            assert!(v["public_port"].is_null());
        }
        let v = check(&[("PUBLIC_API_ORIGIN", "https://example.test:8443/")], &[]);
        assert_eq!(v["public_port"], 8443);
        assert_eq!(v["public_origin"], "https://example.test:8443");
        let v = check(&[("PUBLIC_API_ORIGIN", "https://[2001:db8::8]:443")], &[]);
        assert_eq!(v["public_host"], "[2001:db8::8]");
        assert_eq!(v["public_port"], 443);
    }

    #[test]
    fn lan_accepts_only_private_unicast_and_normalizes_ula() {
        for ip in [
            "0.0.0.0",
            "127.0.0.1",
            "169.254.1.2",
            "203.0.113.8",
            "100.64.0.2",
            "224.0.0.1",
            "255.255.255.255",
            "172.17.0.2",
            "172.30.219.10",
            "::",
            "::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1",
            "::ffff:192.168.0.1",
            "not an address",
        ] {
            let v = check(&[("UNYRI_LAN_ADDRESS", ip)], &[]);
            assert!(v["lan"]["address"].is_null(), "{ip}");
            assert_eq!(v["lan"]["state"], "invalid");
        }
        let v = check(
            &[
                ("UNYRI_LAN_ADDRESS", "FD12:3456::0001"),
                ("UNYRI_LAN_PORT", "65535"),
                ("UNYRI_LAN_DETECTED_AT", "2026-11-18T12:00:00Z"),
            ],
            &[],
        );
        assert_eq!(v["lan"]["address"], "fd12:3456::1");
        assert_eq!(v["lan"]["state"], "detected");
        assert_eq!(v["lan"]["port"], 65535);
        let v = check(
            &[
                ("UNYRI_LAN_ADDRESS", "10.20.0.5"),
                ("UNYRI_SUBNET", "10.20.0.0/24"),
            ],
            &[],
        );
        assert!(v["lan"]["address"].is_null());
        let v = check(
            &[
                ("UNYRI_LAN_ADDRESS", "10.0.0.5"),
                ("UNYRI_HOSTING_KIND", "vps"),
            ],
            &[],
        );
        assert_eq!(v["lan"]["state"], "not_applicable");
        assert!(v["lan"]["address"].is_null());
    }

    #[test]
    fn invalid_ports_timestamps_and_enums_never_escape() {
        for p in ["0", "65536", "-1", "+443", "443.0", "error"] {
            let v = check(
                &[
                    ("UNYRI_LAN_PORT", p),
                    ("TURN_MIN_PORT", p),
                    ("TURN_MAX_PORT", "65535"),
                ],
                &[],
            );
            assert!(v["lan"]["port"].is_null());
            assert_eq!(v["lan"]["state"], "invalid");
            assert!(v["calls"]["relay_min"].is_null());
            assert!(v["calls"]["relay_max"].is_null());
        }
        for t in [
            "yesterday",
            "2026-11-18",
            "2026-11-18T12:00:00",
            "2026-99-18T12:00:00Z",
        ] {
            let v = check(&[("UNYRI_LAN_DETECTED_AT", t)], &[]);
            assert!(v["lan"]["observed_at"].is_null());
            assert_eq!(v["lan"]["state"], "invalid");
        }
        let v = check(
            &[
                ("UNYRI_HOSTING_KIND", "garbage"),
                ("UNYRI_INGRESS_MODE", "proxy"),
                ("UNYRI_TUNNEL_PROVIDER", "error"),
                ("TURN_RELAY_PROTOCOL", "icmp"),
            ],
            &[],
        );
        assert!(v["hosting"].is_null());
        assert!(v["ingress"]["mode"].is_null());
        assert!(v["ingress"]["provider"].is_null());
        assert!(v["calls"]["relay_protocol"].is_null());
        for provider in ["tailscale_funnel", "cloudflare"] {
            let v = check(
                &[
                    ("UNYRI_INGRESS_MODE", "tunnel"),
                    ("UNYRI_TUNNEL_PROVIDER", provider),
                ],
                &[],
            );
            assert_eq!(v["ingress"]["provider"], provider);
        }
        for ip in [
            "10.0.0.1",
            "0.0.0.0",
            "::ffff:203.0.113.8",
            "fd12::1",
            "error",
        ] {
            let v = check(&[("TURN_EXTERNAL_IP", ip)], &[]);
            assert_eq!(v["external_ip"]["state"], "invalid");
            assert!(v["external_ip"]["address"].is_null());
        }
    }

    #[test]
    fn calls_use_effective_urls_and_reject_conflicting_manifests() {
        for urls in [
            vec!["turn:example.test:0"],
            vec!["turn:example.test:65536"],
            vec!["turn:example.test:3478", "turn:example.test:3479"],
            vec!["turn:user:secret@example.test:3478"],
            vec!["turns:example.test:5349?transport=udp"],
        ] {
            let v = check(&[], &urls);
            assert!(v["calls"]["turn_port"].is_null());
            assert!(v["calls"]["transports"].is_null());
        }
        let v = check(
            &[("TURN_MIN_PORT", "60000"), ("TURN_MAX_PORT", "50000")],
            &["turn:example.test:1"],
        );
        assert_eq!(v["calls"]["turn_port"], 1);
        assert_eq!(v["calls"]["transports"], json!(["udp"]));
        assert!(v["calls"]["relay_min"].is_null());
        assert!(v["calls"]["relay_max"].is_null());
    }
}

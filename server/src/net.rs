use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use axum::extract::{ConnectInfo, FromRequestParts};
use axum::http::request::Parts;

use crate::error::AppError;
use crate::state::AppState;

/// A CIDR network used to decide which directly-connected peer is a trusted
/// reverse proxy. Parsing is deliberately dependency-free so the deployment
/// does not need another crate for a few IP comparisons.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct IpNet {
    network: IpAddr,
    prefix: u8,
}

impl IpNet {
    pub fn parse(value: &str) -> Option<Self> {
        let value = value.trim();
        let (address, prefix) = match value.split_once('/') {
            Some((address, prefix)) => (address.trim(), Some(prefix.trim())),
            None => (value, None),
        };
        let network = address.parse::<IpAddr>().ok()?;
        let max = if network.is_ipv4() { 32 } else { 128 };
        let prefix = match prefix {
            Some(prefix) => prefix.parse::<u8>().ok()?,
            None => max,
        };
        if prefix > max { return None; }
        Some(Self { network, prefix })
    }

    pub fn contains(&self, address: IpAddr) -> bool {
        match (self.network, address) {
            (IpAddr::V4(network), IpAddr::V4(address)) => {
                if self.prefix == 0 { return true; }
                let shift = 32 - self.prefix;
                (u32::from(network) >> shift) == (u32::from(address) >> shift)
            }
            (IpAddr::V6(network), IpAddr::V6(address)) => {
                if self.prefix == 0 { return true; }
                let shift = 128 - self.prefix;
                (u128::from(network) >> shift) == (u128::from(address) >> shift)
            }
            _ => false,
        }
    }
}

fn parse_forwarded_address(value: &str) -> Option<IpAddr> {
    let value = value.trim().trim_matches('"');
    if value.is_empty() || value.eq_ignore_ascii_case("unknown") { return None; }
    if let Ok(address) = value.parse::<IpAddr>() { return Some(address); }
    if let Ok(address) = value.parse::<SocketAddr>() { return Some(address.ip()); }
    if let Some(rest) = value.strip_prefix('[') {
        return rest.split_once(']').and_then(|(host, _)| host.parse().ok());
    }
    None
}

/// Pick the client address from a proxy chain.
///
/// The direct peer is trusted only when it belongs to `trusted_proxies`.
/// Walking X-Forwarded-For from right to left skips those trusted hops and
/// returns the first untrusted address, i.e. the address the last trusted
/// proxy actually saw. A direct client that sends a forged header is not in
/// the trusted set, so its header is ignored entirely.
pub fn client_address(peer: IpAddr, forwarded: Option<&str>, trusted: &[IpNet]) -> IpAddr {
    if !trusted.iter().any(|network| network.contains(peer)) { return peer; }
    let Some(forwarded) = forwarded else { return peer; };
    let mut addresses = Vec::new();
    for entry in forwarded.split(',') {
        // A malformed hop is an unknown trust boundary. Fail closed to the
        // directly connected peer instead of skipping it and trusting an
        // address on the far side. This can only make the limiter more
        // conservative (shared proxy bucket), never spoofable.
        let Some(address) = parse_forwarded_address(entry) else { return peer; };
        addresses.push(address);
    }
    if addresses.is_empty() { return peer; }
    for address in addresses.iter().rev() {
        if !trusted.iter().any(|network| network.contains(*address)) { return *address; }
    }
    addresses.first().copied().unwrap_or(peer)
}

fn single_forwarded_header<'a>(mut values: impl Iterator<Item = Option<&'a str>>) -> Option<&'a str> {
    let first = values.next()??;
    if values.next().is_some() { None } else { Some(first) }
}

fn client_address_optional(
    peer: Option<IpAddr>,
    forwarded: Option<&str>,
    trusted: &[IpNet],
) -> IpAddr {
    match peer {
        // Missing ConnectInfo means there is no proven direct peer. Never
        // treat the fallback loopback as a trusted proxy or consume XFF.
        None => IpAddr::V4(Ipv4Addr::LOCALHOST),
        Some(peer) => client_address(peer, forwarded, trusted),
    }
}

/// Authenticated request client address. `ConnectInfo` is used for the direct
/// peer; forwarded headers are only considered when that peer is a trusted
/// proxy from `TRUSTED_PROXIES`.
#[derive(Clone, Copy, Debug)]
pub struct ClientIp(pub IpAddr);

impl ClientIp {
    pub fn ip(self) -> IpAddr { self.0 }
}

#[axum::async_trait]
impl FromRequestParts<AppState> for ClientIp {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        let peer = parts.extensions.get::<ConnectInfo<SocketAddr>>().map(|connect| connect.0.ip());
        // Multiple header lines are ambiguous for trust decisions. Treat them
        // as no usable proxy chain and fall back to the direct peer.
        let forwarded = single_forwarded_header(
            parts.headers.get_all("x-forwarded-for").iter().map(|value| value.to_str().ok()),
        );
        Ok(Self(client_address_optional(peer, forwarded, &state.config.trusted_proxies)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn trusted() -> Vec<IpNet> {
        vec![
            IpNet::parse("127.0.0.1/32").unwrap(),
            IpNet::parse("::1/128").unwrap(),
            IpNet::parse("10.0.0.0/8").unwrap(),
        ]
    }

    #[test]
    fn parses_ipv4_and_ipv6_cidrs() {
        assert!(IpNet::parse("192.0.2.0/24").unwrap().contains("192.0.2.9".parse().unwrap()));
        assert!(!IpNet::parse("192.0.2.0/24").unwrap().contains("192.0.3.9".parse().unwrap()));
        assert!(IpNet::parse("2001:db8::/32").unwrap().contains("2001:db8::1".parse().unwrap()));
        assert!(!IpNet::parse("2001:db8::/32").unwrap().contains("2001:db9::1".parse().unwrap()));
        assert!(IpNet::parse("0.0.0.0/0").unwrap().contains("203.0.113.7".parse().unwrap()));
        assert!(IpNet::parse("::/0").unwrap().contains("2001:db8::1".parse().unwrap()));
        assert!(IpNet::parse("192.0.2.1/33").is_none());
    }

    #[test]
    fn trusted_chain_uses_client_not_proxy_address() {
        let peer: IpAddr = "10.0.0.2".parse().unwrap();
        let address = client_address(peer, Some("203.0.113.9, 10.0.0.2"), &trusted());
        assert_eq!(address, "203.0.113.9".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn forged_header_from_untrusted_peer_is_ignored() {
        let peer: IpAddr = "203.0.113.50".parse().unwrap();
        let address = client_address(peer, Some("198.51.100.7"), &trusted());
        assert_eq!(address, peer);
    }

    #[test]
    fn malformed_hop_fails_closed_instead_of_crossing_an_unknown_boundary() {
        let peer: IpAddr = "10.0.0.2".parse().unwrap();
        assert_eq!(client_address(peer, Some("unknown, 198.51.100.7"), &trusted()), peer);
        assert_eq!(client_address(peer, Some("198.51.100.7, unknown"), &trusted()), peer);
        assert_eq!(client_address(peer, Some("not-an-ip"), &trusted()), peer);
        assert_eq!(client_address(peer, Some(""), &trusted()), peer);
        assert_eq!(client_address(peer, None, &trusted()), peer);
    }

    #[test]
    fn missing_connect_info_never_uses_forwarded_headers() {
        let forwarded = Some("198.51.100.7");
        assert_eq!(
            client_address_optional(None, forwarded, &trusted()),
            IpAddr::V4(Ipv4Addr::LOCALHOST),
        );
        assert_eq!(
            client_address_optional(Some("10.0.0.2".parse().unwrap()), forwarded, &trusted()),
            "198.51.100.7".parse::<IpAddr>().unwrap(),
        );
    }

    #[test]
    fn multiple_forwarded_header_lines_are_not_combined() {
        assert_eq!(single_forwarded_header([Some("198.51.100.7")].into_iter()), Some("198.51.100.7"));
        assert_eq!(single_forwarded_header([Some("198.51.100.7"), Some("203.0.113.9")].into_iter()), None);
        assert_eq!(single_forwarded_header([None].into_iter()), None);
        assert_eq!(single_forwarded_header(std::iter::empty()), None);
    }

    #[test]
    fn ipv6_forwarded_chain_is_parsed() {
        let peer: IpAddr = "::1".parse().unwrap();
        let address = client_address(peer, Some("[2001:db8::5]:443, ::1"), &trusted());
        assert_eq!(address, "2001:db8::5".parse::<IpAddr>().unwrap());
    }
}

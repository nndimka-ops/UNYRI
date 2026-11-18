//! Static deployment contracts: test the configured ranges as addresses, rather
//! than requiring Docker or making requests to a live relay.
use std::net::IpAddr;

fn denied(config: &str, address: &str) -> bool {
    let address: IpAddr = address.parse().unwrap();
    config.lines().filter_map(|line| line.trim().strip_prefix("- --denied-peer-ip="))
        .filter(|range| !range.starts_with('$'))
        .any(|range| {
            let (first, last) = range.split_once('-').unwrap_or((range, range));
            let first: IpAddr = first.parse().expect("valid denied-peer range start");
            let last: IpAddr = last.parse().expect("valid denied-peer range end");
            matches!((first,last,address), (IpAddr::V4(_),IpAddr::V4(_),IpAddr::V4(_)) | (IpAddr::V6(_),IpAddr::V6(_),IpAddr::V6(_)))
                && first <= address && address <= last
        })
}

#[test]
fn both_turn_configurations_deny_internal_and_embedded_address_routes() {
    for config in [include_str!("../../docker-compose.yml"), include_str!("../../docker-compose.production.yml")] {
        for address in ["0.1.2.3", "10.2.3.4", "100.64.1.2", "127.0.0.1", "169.254.1.1",
            "172.16.1.2", "192.168.1.2", "198.18.1.2", "224.1.2.3", "255.255.255.255",
            "::", "::1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "64:ff9b:1::a00:1",
            "fc00::1", "fdff:ffff::1", "fe80::1", "fec0::1", "ff02::1", "2002:7f00:1::1", "2001:0::1"] {
            assert!(denied(config, address), "relay must refuse {address}");
        }
        for address in ["8.8.8.8", "1.1.1.1", "2001:4860:4860::8888"] {
            assert!(!denied(config, address), "public relay peer {address} must remain usable");
        }
        // Relay-to-relay calls (both phones on mobile networks) send media to the
        // relay's own public address; a rule denying it breaks those calls.
        assert!(!config.lines().any(|line| line.contains("--denied-peer-ip=${TURN")),
            "never deny the relay's own address");
    }
}

#[test]
fn infrastructure_images_have_specific_release_tags() {
    for config in [include_str!("../../docker-compose.yml"), include_str!("../../docker-compose.production.yml"),
        include_str!("../../docker-compose.https.yml"), include_str!("../../scripts/qa/isolated-compose.yaml")] {
        for image in config.lines().filter_map(|line| line.trim().strip_prefix("image: ")) {
            let (_, tag) = image.rsplit_once(':').expect("explicit image tag");
            assert_ne!(tag, "latest");
            // PostgreSQL numbers maintenance releases MAJOR.PATCH (16.10),
            // while Redis/coturn use MAJOR.MINOR.PATCH.
            let required = if image.starts_with("postgres:") { 2 } else { 3 };
            assert!(tag.starts_with("RELEASE.") || tag.split('-').next().unwrap().split('.').count()>=required,
                "image needs a full release version: {image}");
        }
    }
}

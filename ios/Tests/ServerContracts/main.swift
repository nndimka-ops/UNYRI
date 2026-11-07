import Foundation

let valid: [(String, String)] = [

    ("example.com", "https://example.com"),
    ("HTTPS://Example.COM:443/", "https://example.com"),

    ("https://[::1]:8443", "https://[::1]:8443")
]
for (input, expected) in valid {
    let actual = try AppConfig.normalizedServerURL(input).absoluteString
    precondition(actual == expected, "Unexpected normalized server: \(actual)")
}
for input in ["", " ", "ftp://example.com", "https://user:password@example.com", "https://example.com/api", "https://example.com?token=secret", "https://example.com#fragment", "http://bad host", "http://example.com:0", "http://example.com:65536"] {
    do {
        _ = try AppConfig.normalizedServerURL(input)
        fatalError("Accepted invalid server: \(input)")
    } catch APIError.invalidURL { }
}
print("Server address contract checks passed")

#if DEBUG || UNYRI_ALPHA_LAN
let debugURL = try AppConfig.normalizedServerURL("http://localhost:80")
precondition(debugURL.absoluteString == "http://localhost")
#else
for input in ["http://localhost", "http://192.168.0.106:8080", "http://example.com"] {
    do { _ = try AppConfig.normalizedServerURL(input); fatalError("Release accepted cleartext") }
    catch APIError.invalidURL { }
}
#endif

#if UNYRI_ALPHA_LAN
let alphaLocal = try AppConfig.normalizedServerURL("http://192.168.0.106:8080")
precondition(alphaLocal.host == "192.168.0.106")
do { _ = try AppConfig.normalizedServerURL("http://example.com"); fatalError("Alpha accepted public cleartext") } catch APIError.invalidURL { }
#endif

let oldLan = "http://192.168.0.106:8080"
let tlsLan = "https://192.168.0.106:8443"
precondition(AppConfig.lanUpgrade(configured: oldLan, bundled: oldLan, enabled: true)?.absoluteString == tlsLan)
precondition(AppConfig.lanUpgrade(configured: oldLan, bundled: oldLan, enabled: false) == nil)
precondition(AppConfig.lanUpgrade(configured: "http://192.168.0.107:8080", bundled: oldLan, enabled: true) == nil)
precondition(AppConfig.lanUpgrade(configured: oldLan, bundled: "https://example.com", enabled: true) == nil)
precondition(AppConfig.lanUpgrade(configured: tlsLan, bundled: oldLan, enabled: true) == nil)
precondition(AppConfig.storageIdentity(transport: tlsLan, recorded: oldLan) == oldLan)
precondition(AppConfig.storageIdentity(transport: tlsLan, recorded: nil) == tlsLan)
precondition(AppConfig.storageIdentity(transport: "https://example.com", recorded: oldLan) == "https://example.com")
print("LAN TLS upgrade preserves only the explicitly migrated server identity")

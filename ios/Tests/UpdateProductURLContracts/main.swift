import Foundation

@main struct UpdateProductURLContracts {
    static func main() {
        // Fixture products only: not claims about UNYRI's real store identity.
        let store = "https://apps.apple.com/app/id1234567890"
        let flight = "https://testflight.apple.com/join/Ab12Cd34"
        func admitted(_ value: String?, pin: String? = "https://apps.apple.com/app/id1234567890") -> String? {
            AppVersion.trustedUpdateURL(value, publisherURL: pin)?.absoluteString
        }
        for value in [store, "https://itunes.apple.com/app/id1234567890",
                      "https://apps.apple.com/ua/app/unyri-messenger/id1234567890",
                      "https://apps.apple.com/us/app/id1234567890"] {
            precondition(admitted(value) == value, "Same publisher product must remain usable")
        }
        precondition(admitted(flight, pin: flight) == flight)
        for value in ["https://apps.apple.com/app/id9999999999",
                      "https://testflight.apple.com/join/Zz98Yy76", flight,
                      "https://apps.apple.com/app/id12345678901", "https://apps.apple.com/app/id01234567890",
                      "https://apps.apple.com/app/id1234567890/elsewhere",
                      "https://apps.apple.com/app/id1234567890?url=https://evil.invalid",
                      "https://apps.apple.com/app/id1234567890#redirect",
                      "https://apps.apple.com/app/id1234567890?l=ru",
                      "https://apps.apple.com/xx/../app/id1234567890",
                      "https://apps.apple.com/app/id%31%32%33%34%35%36%37%38%39%30",
                      "https://apps.apple.com/app/unyri%2Fother/id1234567890",
                      "https://apps.apple.com/app//id1234567890",
                      "https://apps.apple.com//app/id1234567890",
                      "https://apps.apple.com/app/id1234567890/",
                      "http://apps.apple.com/app/id1234567890",
                      "https://apps.apple.com.evil.invalid/app/id1234567890",
                      "https://apps.apple.com./app/id1234567890",
                      "https://user@apps.apple.com/app/id1234567890",
                      "https://apps.apple.com:443/app/id1234567890",
                      "https://testflight.apple.com/join/Ab12Cd34/extra", ""] {
            precondition(admitted(value) == store, "Unapproved destination must fall back to publisher pin: \(value)")
        }
        for value in ["https://testflight.apple.com/join/ab12Cd34",
                      "https://testflight.apple.com/join/Ab12Cd34?x=1",
                      "https://testflight.apple.com/join/Ab12Cd34#x",
                      "https://testflight.apple.com/join/%41b12Cd34",
                      "https://testflight.apple.com/other/Ab12Cd34", store] {
            precondition(admitted(value, pin: flight) == flight, "TestFlight codes/paths must match exactly")
        }
        for pin in [nil, "", "https://example.invalid/app/id1234567890",
                    "https://apps.apple.com/anything", "https://testflight.apple.com/join/abc",
                    "https://apps.apple.com/app/id1234567890?redirect=x"] as [String?] {
            precondition(admitted(store, pin: pin) == nil && admitted(flight, pin: pin) == nil,
                         "Server metadata must never bootstrap a missing/invalid publisher pin")
        }
        precondition(admitted(nil) == store, "Missing server URL may use the publisher's own URL")
        print("Publisher-bound App Store and TestFlight URL contracts passed")
    }
}

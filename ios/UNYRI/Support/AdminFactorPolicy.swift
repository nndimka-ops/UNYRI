import Foundation

/// Shared by the registered profile screen and standalone administration
/// contracts. Keep this policy independent of the optional Admin DTO pack.
enum AdminFactorPolicy: String, CaseIterable {
    case authenticator
    case paperKey = "paper_key"
    case both

    var requiresAuthenticator: Bool { self != .paperKey }
    var requiresPaperKey: Bool { self != .authenticator }

    static func parse(protocolVersion: Int?, policy: String?) throws -> Self {
        guard (protocolVersion ?? 1) >= 2 else { return .both }
        guard let policy, let result = Self(rawValue: policy) else {
            throw NSError(domain: "COCO.AdminAuthentication", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Unsupported administrator sign-in policy. Update UNYRI or use the Windows Admin Console."])
        }
        return result
    }

    static func acceptsServerPin(previous: String?, pending: String?, server: String) -> Bool {
        (previous == nil && pending == nil) || previous?.components(separatedBy: "|").first == server || pending?.components(separatedBy: "|").first == server
    }
}

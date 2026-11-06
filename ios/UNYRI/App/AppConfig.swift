import Foundation

/// Central configuration for the iOS client.
enum AppConfig {
    static let publicDefaultOrigin = "https://8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud"
    private static let legacyLan = "http://192.168.0.106:8080"
    private static let secureLan = "https://192.168.0.106:8443"
    private static func configuredInfo(_ key: String, legacy: String) -> Any? {
        if let value = Bundle.main.object(forInfoDictionaryKey: key) { return value }
        return Bundle.main.object(forInfoDictionaryKey: legacy)
    }
    private static let tlsIdentityKey = "coco.tlsIdentityOrigin"
    static let serverPreferenceKey = "coco.serverURL"

    /// Existing explicit selections are preserved by the startup continuity
    /// preflight before authentication or account workspaces are opened.
    static var serverAddressForEditing: String {
        if configuredInfo("UNYRI_LAN_TLS_MIGRATION", legacy: "COCO_LAN_TLS_MIGRATION") as? Bool == true { return apiBaseURL.absoluteString }
        if let saved = UserDefaults.standard.string(forKey: serverPreferenceKey),
           let url = try? normalizedServerURL(saved) { return url.absoluteString }
        if let raw = configuredInfo("UNYRI_API_BASE_URL", legacy: "COCO_API_BASE_URL") as? String,
           let url = try? normalizedServerURL(raw) { return url.absoluteString }
        return defaultBaseURL.absoluteString
    }

    static func normalizedServerURL(_ input: String) throws -> URL {
        let raw = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidate = raw.contains("://") ? raw : "https://" + raw
        guard !raw.isEmpty, !raw.contains(where: { $0.isWhitespace }),
              var parts = URLComponents(string: candidate),
              let scheme = parts.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/",
              parts.port == nil || (1...65535).contains(parts.port!),
              !host.contains("%") else { throw APIError.invalidURL }
        if scheme == "http" {
            #if DEBUG || UNYRI_ALPHA_LAN
            let local = host == "localhost" || host == "::1" || host == "[::1]" || host.hasSuffix(".local")
            let octets = host.split(separator: ".").compactMap { Int($0) }
            let privateIPv4 = octets.count == 4 && octets.allSatisfy { (0...255).contains($0) } &&
                (octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && (16...31).contains(octets[1])))
            guard local || privateIPv4 else { throw APIError.invalidURL }
            #else
            throw APIError.invalidURL
            #endif
        }
        parts.scheme = scheme
        parts.host = host.lowercased()
        parts.path = ""
        if (scheme == "https" && parts.port == 443) || (scheme == "http" && parts.port == 80) { parts.port = nil }
        guard let url = parts.url else { throw APIError.invalidURL }
        return url
    }

    static func saveServer(_ url: URL) {
        UserDefaults.standard.set(url.absoluteString, forKey: serverPreferenceKey)
    }

    /// Existing installations keep their Signal namespace on the original
    /// server; other servers must never share identity/session key storage.
    static var signalServerPrefix: String {
        guard storageOrigin != defaultBaseURL.absoluteString else { return "" }
        return "server." + Data(storageOrigin.utf8).base64EncodedString() + "."
    }
    /// Base URL of the UNYRI backend.
    ///
    /// The selected server wins over the bundled public default. Native QA
    /// keeps its explicit UNYRI_API_BASE_URL build override for private loopback.
    static var apiBaseURL: URL {
        let saved = UserDefaults.standard.string(forKey: serverPreferenceKey).flatMap { try? normalizedServerURL($0) }
        let configured = saved ?? defaultBaseURL
        if let secure = lanUpgrade(configured: configured.absoluteString, bundled: defaultBaseURL.absoluteString,
                                   enabled: configuredInfo("UNYRI_LAN_TLS_MIGRATION", legacy: "COCO_LAN_TLS_MIGRATION") as? Bool == true) {
            UserDefaults.standard.set(legacyLan, forKey: tlsIdentityKey)
            UserDefaults.standard.set(secureLan, forKey: serverPreferenceKey)
            return secure
        }
        return configured
    }

    /// Transport upgrade must not move the on-device databases, media or Signal keys.
    static var storageOrigin: String {
        let transport = apiBaseURL.absoluteString
        return storageIdentity(transport: transport, recorded: UserDefaults.standard.string(forKey: tlsIdentityKey))
    }

    static func lanUpgrade(configured: String, bundled: String, enabled: Bool) -> URL? {
        guard enabled, configured == legacyLan, bundled == legacyLan else { return nil }
        return URL(string: secureLan)
    }
    static func storageIdentity(transport: String, recorded: String?) -> String {
        transport == secureLan && recorded == legacyLan ? legacyLan : transport
    }

    static var defaultBaseURL: URL {
        configuredDefaultOrigin(configuredInfo("UNYRI_API_BASE_URL", legacy: "COCO_API_BASE_URL") as? String)
    }

    static func configuredDefaultOrigin(_ raw: String?) -> URL {
        if let raw,
           !raw.isEmpty,
           let url = try? normalizedServerURL(raw) {
            return url
        }
        return URL(string: publicDefaultOrigin)!
    }

    /// WebSocket endpoint derived from `apiBaseURL` (`/ws`), mapping http ->
    /// ws and https -> wss so the dev (cleartext) and future prod (TLS) hosts
    /// both work without a second config knob.
    static var wsURL: URL {
        guard var components = URLComponents(url: apiBaseURL, resolvingAgainstBaseURL: false) else {
            return URL(string: publicDefaultOrigin.replacingOccurrences(of: "https://", with: "wss://") + "/ws")!
        }
        components.scheme = (components.scheme == "https") ? "wss" : "ws"
        components.path = "/ws"
        components.query = nil
        components.fragment = nil
        return components.url ?? URL(string: publicDefaultOrigin.replacingOccurrences(of: "https://", with: "wss://") + "/ws")!
    }

    /// One explicit startup operation, never a per-row lookup. Read a checked
    /// session identity and prove an existing workspace before adopting any
    /// previously unbound server. Failed proof never modifies preferences.
    static func prepareServerOrigin(defaults: UserDefaults = .standard, choice: String? = nil,
        identity: () throws -> String?, installation: () throws -> Bool,
        hasWorkspaces: () throws -> Bool, workspace: (String, String) throws -> Bool) throws -> URL {
        let raw = defaults.object(forKey: serverPreferenceKey)
        if raw != nil, !(raw is String) { throw UnyriServerOriginContinuity.Failure(.invalidPreference) }
        if defaults.object(forKey: tlsIdentityKey) != nil, defaults.string(forKey: tlsIdentityKey) == nil {
            throw UnyriServerOriginContinuity.Failure(.invalidPreference)
        }
        let decision = try UnyriServerOriginContinuity.resolve(preference: raw as? String,
            bundled: defaultBaseURL, recordedTLSIdentity: defaults.string(forKey: tlsIdentityKey), choice: choice,
            identity: identity, installation: installation, hasWorkspaces: hasWorkspaces, workspace: workspace)
        if decision.persist {
            defaults.set(decision.url.absoluteString, forKey: serverPreferenceKey)
            guard defaults.string(forKey: serverPreferenceKey) == decision.url.absoluteString else {
                throw UnyriServerOriginContinuity.Failure(.persistence)
            }
        }
        return decision.url
    }
}

/// Pure startup decision. Only the historical LAN origins may supply evidence
/// for an unbound authenticated installation; arbitrary server probing and
/// token-based discovery are deliberately absent.
enum UnyriServerOriginContinuity {
    enum Reason { case sessionUnreadable, invalidIdentity, invalidPreference, workspaceUnreadable,
        unprovenWorkspace, ambiguous, invalidChoice, legacyTransportUnavailable, persistence }
    struct Failure: Error {
        let reason: Reason
        let choices: [URL]
        init(_ reason: Reason, choices: [URL] = []) { self.reason = reason; self.choices = choices }
    }
    struct Decision { let url: URL; let persist: Bool }
    static let legacyHTTP = "http://192.168.0.106:8080"
    static let legacyHTTPS = "https://192.168.0.106:8443"

    static func resolve(preference: String?, bundled: URL, recordedTLSIdentity: String?, choice: String? = nil,
        identity: () throws -> String?, installation: () throws -> Bool,
        hasWorkspaces: () throws -> Bool, workspace: (String, String) throws -> Bool) throws -> Decision {
        var selectedPreference: URL?
        if let preference {
            guard let url = try? AppConfig.normalizedServerURL(preference) else { throw Failure(.invalidPreference) }
            selectedPreference = url
        }
        let user: String?
        do { user = try identity() } catch { throw Failure(.sessionUnreadable) }
        if let user, UUID(uuidString: user) == nil { throw Failure(.invalidIdentity) }
        if let selectedPreference { return Decision(url: selectedPreference, persist: false) }
        guard let user else { return Decision(url: bundled, persist: true) }
        let installed: Bool, retainedWorkspace: Bool
        do { installed = try installation(); retainedWorkspace = try hasWorkspaces() }
        catch { throw Failure(.workspaceUnreadable) }
        // Keychain can outlive uninstall. Without the installation sentinel or
        // any workspace this is a fresh install, not an unidentified old account.
        // Auth.bootstrap's existing local checks retire those stale credentials
        // before its first authentication/network request.
        if !installed && !retainedWorkspace { return Decision(url: bundled, persist: true) }

        let origins = recordedTLSIdentity == legacyHTTP ? [legacyHTTPS] : [legacyHTTP, legacyHTTPS]
        var candidates = [URL]()
        var unavailableTransport = false
        for origin in origins {
            let storage = AppConfig.storageIdentity(transport: origin, recorded: recordedTLSIdentity)
            let proven: Bool
            do { proven = try workspace(storage, user) } catch { throw Failure(.workspaceUnreadable) }
            guard proven else { continue }
            guard let url = try? AppConfig.normalizedServerURL(origin) else { unavailableTransport = true; continue }
            candidates.append(url)
        }
        if let choice {
            guard let selected = try? AppConfig.normalizedServerURL(choice), candidates.contains(selected) else { throw Failure(.invalidChoice) }
            return Decision(url: selected, persist: true)
        }
        if candidates.count == 1 { return Decision(url: candidates[0], persist: true) }
        if candidates.count > 1 { throw Failure(.ambiguous, choices: candidates) }
        throw Failure(unavailableTransport ? .legacyTransportUnavailable : .unprovenWorkspace)
    }
}

/// The preflight and Auth.bootstrap use the same bounded workspace proof.
/// Missing and unreadable files are intentionally different outcomes.
enum UnyriServerWorkspaceEvidence {
    static func installationExists(support: URL) throws -> Bool {
        let file = support.appendingPathComponent("COCOInstallation", isDirectory: true).appendingPathComponent("continuity-v1")
        do {
            guard try Data(contentsOf: file) == Data("COCO installation v1".utf8) else { throw CocoaError(.fileReadCorruptFile) }
            return true
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile { return false }
    }

    static func hasAnyWorkspace(support: URL) throws -> Bool {
        let root = support.appendingPathComponent("COCO", isDirectory: true)
        do {
            guard try root.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true else { throw CocoaError(.fileReadCorruptFile) }
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile { return false }
        var failure: Error?
        guard let entries = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles], errorHandler: { _, error in failure = error; return false }) else { throw CocoaError(.fileReadUnknown) }
        let first = entries.nextObject()
        if let failure { throw failure }
        return first != nil
    }

    static func hasWorkspace(support: URL, origin: String, user: String) throws -> Bool {
        guard UUID(uuidString: user) != nil else { throw CocoaError(.fileReadCorruptFile) }
        let server = Data(origin.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
        let database = support.appendingPathComponent("COCO", isDirectory: true)
            .appendingPathComponent(server, isDirectory: true).appendingPathComponent(user, isDirectory: true)
            .appendingPathComponent("messages.sqlite3")
        do {
            guard try database.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { throw CocoaError(.fileReadCorruptFile) }
            let handle = try FileHandle(forReadingFrom: database)
            try handle.close()
            return true
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile { return false }
    }
}

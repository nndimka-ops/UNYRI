import Foundation

/// Coalesces admission notifications only; each accepted item is already durable.
/// Send/status and incoming-message notifications do not pass through this scope.
struct MediaAdmissionBatch {
    private var depth = 0
    private var touchedChats = Set<String>()

    mutating func begin() { depth += 1 }

    mutating func accepted(chatId: String) -> Set<String>? {
        guard depth > 0 else { return [chatId] }
        touchedChats.insert(chatId)
        return nil
    }

    mutating func end() -> Set<String> {
        guard depth > 0 else { return [] }
        depth -= 1
        guard depth == 0 else { return [] }
        let result = touchedChats
        touchedChats.removeAll()
        return result
    }
}

/// Local send invariant violations that a retry cannot fix.
enum OutboxError: Error, LocalizedError {
    case duplicateRecipientDevice
    case identityChanged
    case mediaPreparation(String)
    var errorDescription: String? {
        switch self {
        case .duplicateRecipientDevice: return "Duplicate recipient device."
        case .identityChanged: return "Verify security keys before retrying."
        case .mediaPreparation(let reason): return reason
        }
    }
}

/// How a failed send attempt should be handled by the durable outbox
/// (mirrors Android's `OutboxFailureClass`).
enum OutboxFailureClass: Equatable {
    /// Keep pending and schedule a retry (network/5xx/recoverable).
    case transient

    /// Retrying cannot help without a state/content change — mark failed.
    case permanent

    /// The session is dead — the auth layer's logout policy takes over.
    case authTerminal
}

/// Pure decision boundary for the durable outbox. Never treats a transient
/// network/server failure as permanent — a message stays pending and retries
/// until the backend accepts it or the session is authoritatively dead.
enum OutboxPolicy {
    static func classify(_ error: Error) -> OutboxFailureClass {
        switch error {
        case is MessageTextPolicyError:
            return .permanent
        case let apiError as APIError:
            switch apiError {
            case .network, .decoding:
                return .transient
            case .http(let status):
                return status == 408 || status == 429 || (500...599).contains(status) ? .transient : .permanent
            case .invalidURL:
                return .permanent
            case .server(let code, let message):
                switch code {
                case "rate_limited", "internal_error":
                    return .transient
                case "invalid_refresh_token", "session_revoked", "unauthorized":
                    return .authTerminal
                case "validation_error":
                    // Recoverable own-device coverage: rebuilding envelopes on
                    // the next attempt refreshes bundles. Other validation
                    // errors cannot be fixed by a retry.
                    return message.contains("Every other active device") ? .transient : .permanent
                default:
                    return .permanent
                }
            case .missingSession:
                return .authTerminal
            }
        case is OutboxError:
            // Local invariant violation (e.g. duplicate recipient device) —
            // a retry cannot fix it.
            return .permanent
        case let fileError as CocoaError where fileError.code == .fileReadNoSuchFile || fileError.code == .fileNoSuchFile:
            // A removed local attachment must be selected again; a timer cannot
            // restore it. Other file errors (e.g. a locked device) may recover.
            return .permanent
        default:
            // Unknown/network-ish errors are transient by default — never lose
            // a composed message over them.
            return .transient
        }
    }

    /// Deterministic exponential backoff capped at 60s (1s, 2s, 4s, 8s, 15s,
    /// 30s, 60s), matching Android. A connectivity-restored signal triggers an
    /// immediate flush that skips this wait.
    static func baseBackoffMs(_ retryCount: Int) -> Int64 {
        switch max(retryCount, 0) {
        case 0: return 1_000
        case 1: return 2_000
        case 2: return 4_000
        case 3: return 8_000
        case 4: return 15_000
        case 5: return 30_000
        default: return 60_000
        }
    }
}

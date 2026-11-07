import Combine
import Foundation

enum DirectFileAction: Hashable { case accept, reject, pause, resume, allowBackground, cancel, retry, retrySync, retryPauseSave, retryContinueSave, restart, open, share }

/// Deliberate display projection. No nonce, devices, consent journal, pending
/// ciphertext, mailbox cursor/budget or authoritative mutable state escapes.
struct DirectFilePresentation: Equatable, Identifiable {
    var id: String
    var chatId: String
    var filename: String
    var mimeType: String
    var size: Int64
    var outgoing: Bool
    var phase: DirectFilePhase
    var status: String
    var ordinal: Int64
    var queuePosition: Int?
    var actions: Set<DirectFileAction>
    var backgroundStatus: String? = nil
}

@MainActor final class DirectFileRowState: ObservableObject {
    @Published private(set) var value: DirectFilePresentation?
    @Published private(set) var progress = DirectFileProgress(offset: 0)
    func update(_ value: DirectFilePresentation?) {
        if self.value != value { self.value = value }
    }
    func update(_ progress: DirectFileProgress) {
        if self.progress != progress { self.progress = progress }
    }
}

/// Stable ordering publishes only on admission/phase/filter-relevant changes.
/// ACKs update one row instead of invalidating the Transfers list at four Hz.
@MainActor final class DirectFileListState: ObservableObject {
    @Published private(set) var ids: [String] = []
    @Published private(set) var interruptedPreparations: [DirectFileAdmissionTicket] = []
    func update(ids: [String], interrupted: [DirectFileAdmissionTicket]) {
        if self.ids != ids { self.ids = ids }
        if interruptedPreparations != interrupted { interruptedPreparations = interrupted }
    }
}

enum DirectFileFailureText {
    static func status(_ reason: String?, outgoing: Bool) -> String {
        switch reason {
        case "source_missing", "source_changed": return "The original is unavailable. Choose the file again."
        case "claimed_elsewhere": return "This transfer was accepted on another device."
        case "transfer_unavailable": return "This transfer is no longer available."
        case "integrity_failed", "integrity_mismatch", "verification_failed": return "The file could not be verified. Ask the sender to send it again."
        case "prefix_mismatch": return outgoing ? "The recipient must restart the transfer." : "The saved part differs. Restart the transfer."
        case "disk_full": return "Free up space on this device, then retry."
        case "storage_error": return "Could not read or save the file on this device. Check storage and retry."
        case "offer_send_failed": return "The transfer request was not sent. Retry or cancel it."
        case "call_in_progress": return "Paused during a call. It will continue afterwards."
        case "queued": return outgoing ? "Waiting for a transfer slot on this device" : "Waiting for the other device's transfer slot"
        case "busy": return "Waiting for the other device to finish preparing."
        case "security_error": return "Could not verify the other device. Check the account and retry."
        case "permission_denied": return outgoing ? "Allow access to the original file, then retry." : "Could not save the file on this device. Check access and retry."
        case "unsupported": return "Update UNYRI on both devices and retry."
        case "background": return "Open UNYRI on both devices to continue."
        case "direct_unavailable", "no_direct_route": return "A direct connection is unavailable on this network. Try another network."
        default: return "Waiting for a direct connection. Keep UNYRI open on both devices."
        }
    }
}

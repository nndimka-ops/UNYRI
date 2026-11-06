import Foundation

/// Installation-local scheduling metadata. Never exported through chat/history.
/// The ordinal is durable and independent of wall time and progress updates.
struct DirectFileQueueState: Codable, Equatable, Sendable {
    var ordinal: Int64
    var reservedFutureBytes: Int64
    var identityProofObserved: Bool?
    var remoteCapacityBlocked = false
    var capacityHintCount = 0
    var nextCapacityHintAt: Int64 = 0
    var lastCapacityHintId: String?

    var valid: Bool {
        ordinal > 0 && reservedFutureBytes >= 0 && reservedFutureBytes <= DirectFileOffer.maximumBytes &&
        (0...6).contains(capacityHintCount) && nextCapacityHintAt >= 0 &&
        (lastCapacityHintId == nil || UUID(uuidString: lastCapacityHintId!)?.uuidString.lowercased() == lastCapacityHintId!)
    }
}

/// Pre-offer copy admission has no fabricated offer/hash and no external URL.
/// A restart marks an unfinished ticket as needing explicit re-selection.
struct DirectFileAdmissionTicket: Codable, Equatable, Sendable {
    var id: String
    var outgoing: Bool
    var ordinal: Int64
    var reservedFutureBytes: Int64
    var needsReselection = false
    var displayFilename: String?
    var declaredBytes: Int64?
    var chatId: String?
    var valid: Bool {
        UUID(uuidString: id)?.uuidString.lowercased() == id && ordinal > 0 && reservedFutureBytes >= 0 &&
        reservedFutureBytes <= DirectFileOffer.maximumBytes &&
        (displayFilename == nil || displayFilename!.utf8.count <= 255 && DirectFileOffer.safeFilename(displayFilename!) == displayFilename!) &&
        (declaredBytes == nil || declaredBytes! >= 0 && declaredBytes! <= DirectFileOffer.maximumBytes) &&
        (chatId == nil || UUID(uuidString: chatId!)?.uuidString.lowercased() == chatId!)
    }
}

struct DirectFileAdmissionLedger: Codable, Sendable {
    var accountNamespace: String = ""
    var nextOutboundOrdinal: Int64 = 1
    var nextInboundOrdinal: Int64 = 1
    var tickets: [DirectFileAdmissionTicket] = []

    var valid: Bool {
        !accountNamespace.isEmpty && accountNamespace.utf8.count <= 4096 && nextOutboundOrdinal > 0 && nextInboundOrdinal > 0 && tickets.count <= 16 &&
        tickets.filter(\.outgoing).count <= 8 && tickets.filter({ !$0.outgoing }).count <= 8 &&
        Set(tickets.map(\.id)).count == tickets.count && tickets.allSatisfy(\.valid)
    }
}

/// Additional validation for private journal extensions; absent legacy values
/// remain absent and never acquire a fresh known signal budget.
extension DirectFileRecord {
    var validLocalExtensions: Bool {
        (manualPause == nil || (try? manualPause!.validated()) != nil) &&
        (signalBudget == nil || (try? signalBudget!.validated()) != nil) &&
        (queue == nil || queue!.valid) &&
        (continuedProcessing == nil || continuedProcessing!.valid)
    }
}

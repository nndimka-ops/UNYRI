import Foundation

enum UnyriQuickReactionPolicy {
    static let choices = ["👍", "❤️", "😂", "😮", "😢", "🙏"]
    static func normalize(_ value: String) -> String { value.isEmpty || choices.contains(value) ? value : "👍" }
    static func eligible(status: String, serverId: String?, deleted: Bool, selecting: Bool) -> Bool {
        !selecting && !deleted && serverId != nil && ["sent", "delivered", "read"].contains(status)
    }
}

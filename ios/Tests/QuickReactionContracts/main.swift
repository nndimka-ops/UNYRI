import Foundation
func check(_ condition: Bool, _ message: String) { if !condition { fatalError(message) } }
for status in ["sent", "delivered", "read", "pending", "failed", "decrypt_failed", "identity_changed"] {
    check(UnyriQuickReactionPolicy.eligible(status: status, serverId: "id", deleted: false, selecting: false) == ["sent", "delivered", "read"].contains(status), status)
}
check(!UnyriQuickReactionPolicy.eligible(status: "sent", serverId: nil, deleted: false, selecting: false), "unacknowledged target")
check(!UnyriQuickReactionPolicy.eligible(status: "read", serverId: "id", deleted: true, selecting: false), "deleted target")
check(!UnyriQuickReactionPolicy.eligible(status: "read", serverId: "id", deleted: false, selecting: true), "selection mode")
check(UnyriQuickReactionPolicy.normalize("") == "", "disabled preference")
check(UnyriQuickReactionPolicy.normalize("invalid") == "👍", "invalid preference")
for choice in UnyriQuickReactionPolicy.choices { check(UnyriQuickReactionPolicy.normalize(choice) == choice, "supported preference") }
print("Quick reactions: 18 contracts passed")

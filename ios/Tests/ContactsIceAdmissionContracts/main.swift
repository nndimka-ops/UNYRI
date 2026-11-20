import Foundation

// Only account state is doubled; bind() is extracted unchanged from UNYRIApp.
@MainActor final class ContactsStore {
    var ids: Set<String> = []
    func isKnownPeerContact(_ userID: String) -> Bool { ids.contains(userID.lowercased()) }
}
@MainActor final class UsersRepository {
    let contacts = ContactsStore()
    var contactsStore: ContactsStore? { contacts }
    var ownProfileRetired = false
    var blockedAccountScope: String? = "origin-a|owner-a|generation-1"
    var directChatPeers: Set<String> = []
}
@MainActor final class CallController { var isContact: (String) -> Bool = { _ in false } }

@main struct ContactsIceAdmissionContracts {
    @MainActor static func main() {
        let peer = "aabbccdd-0000-4000-8000-000000000001"
        let controller = CallController()
        let files = CallController()
        var users: UsersRepository? = UsersRepository()
        bind(controller, files, users: users!)
        @MainActor func relay(_ mode: CallPeerToPeer = .contacts) -> Bool {
            precondition(controller.isContact(peer.uppercased()) == files.isContact(peer.uppercased()),
                "Calls and files must use identical real Contacts membership")
            return CallRelayPolicy.relayOnly(mode, peerIsContact: controller.isContact(peer.uppercased()))
        }
        precondition(relay(), "Unavailable membership must stay on TURN")
        users!.directChatPeers.insert(peer)
        precondition(relay(), "A direct chat alone must never authorize direct ICE")
        users!.contacts.ids.insert(peer)
        precondition(!relay(), "A known Contact may use direct ICE, including uppercase wire UUIDs")
        users!.contacts.ids.remove(peer)
        precondition(relay(), "Removing a Contact must affect the next ICE configuration")
        users!.contacts.ids.insert(peer)
        users!.blockedAccountScope = nil
        precondition(relay(), "Retired account/origin/generation must fail closed even with cached ids")
        let other = CallController()
        let otherUsers = UsersRepository()
        let otherFiles = CallController()
        bind(other, otherFiles, users: otherUsers)
        precondition(!other.isContact(peer), "Another account cannot borrow membership")
        users = nil
        precondition(relay(), "Released account state must fail closed")
        precondition(!relay(.everyone), "Everyone remains direct-capable")
        precondition(relay(.nobody), "Nobody remains relay-only")
        otherUsers.contacts.ids.insert(peer)
        precondition(CallRelayPolicy.relayOnly(.nobody, peerIsContact: other.isContact(peer)))
        print("Contacts-only ICE admission contracts passed")
    }
}

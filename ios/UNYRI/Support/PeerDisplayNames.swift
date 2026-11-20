/// Display projections use only explicitly public identifiers.
extension PublicUser {
    var visibleName: String { DisplayNameSanitizer.peer(displayName, unknown: UnyriL10n.string("Unknown user")) }
    var publicIdentityLabel: String { peerIdentityLabel() }
    func peerIdentityLabel(legacyServer: Bool = false) -> String {
        PeerIdentityPolicy.label(publicUsername: publicUsername, unyriNumber: unyriNumber,
                                 legacyUsername: username, legacyServer: legacyServer)
    }
    func contactCaption(legacyServer: Bool = false) -> String {
        PeerIdentityPolicy.contactCaption(publicUsername: publicUsername, unyriNumber: unyriNumber, legacyServer: legacyServer)
    }
}

extension CallInfo {
    var visibleRemoteName: String { DisplayNameSanitizer.peer(remoteDisplayName, unknown: UnyriL10n.string("Unknown user")) }
}

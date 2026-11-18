/// Display projections keep the Codable payloads and history byte-for-byte.
extension PublicUser {
    var visibleName: String { DisplayNameSanitizer.peer(displayName, unknown: UnyriL10n.string("Unknown user")) }
}

extension CallInfo {
    var visibleRemoteName: String { DisplayNameSanitizer.peer(remoteDisplayName, unknown: UnyriL10n.string("Unknown user")) }
}

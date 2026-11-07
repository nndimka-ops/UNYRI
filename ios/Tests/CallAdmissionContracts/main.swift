import Foundation

@main struct AdmissionContracts {
    @MainActor static func main() async {
        var count = 0
        func check(_ value: Bool, _ label: String) { precondition(value, label); count += 1; print("PASS: \(label)") }
        let peer = UUID().uuidString
        let remote = ProbeController()
        remote.api.failure = APIError.server(code: "conflict", message: "busy")
        await remote.startCall(remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .audio)
        check(remote.state == .failed(nil, reason: .busy), "server busy remains a typed busy result")
        check(remote.effects.isEmpty, "busy result does not start media effects")
        let generic = ProbeController()
        generic.api.failure = APIError.server(code: "conflict", message: "Communication is unavailable for this chat.")
        await generic.startCall(remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .audio)
        check(generic.state == .failed(nil, reason: .generic), "unrelated conflicts are never mislabeled as busy")
        let network = ProbeController()
        network.api.failure = APIError.network("synthetic outage")
        await network.startCall(remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .audio)
        check(network.state == .failed(nil, reason: .network), "network failures keep their own explanation")
        CallMediaPermissions.requests = 0
        let room = ProbeController()
        room.externalMediaBusy = { true }
        await room.startCall(remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .video)
        check(room.state == .idle && room.api.createCount == 0, "room owner rejects new call before REST")
        check(CallMediaPermissions.requests == 0, "blocked call does not ask capture permissions")
        check(room.startBlockedMessage == "You are already in a room. Leave it before starting another conversation.", "room block has actionable feedback")
        let info = CallInfo(callId: UUID().uuidString, remoteUserId: peer, remoteDisplayName: "Current", mediaKind: .audio, direction: .outgoing)
        let active = CallUiState.connected(ConnectedState(call: info, isMuted: true))
        let connected = ProbeController(); connected.state = active
        await connected.startCall(remoteUserId: UUID().uuidString, remoteDisplayName: "Second", mediaKind: .video)
        check(connected.state == active && connected.api.createCount == 0, "second call never replaces connected media state")
        check(connected.startBlockedMessage != nil, "existing-call rejection is visible")
        let recording = ProbeController(); recording.externalCaptureBusy = { true }
        await recording.startCall(remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .audio)
        check(recording.api.createCount == 0 && recording.startBlockedMessage == "Finish the current recording before starting a call.", "recording gets a separate explanation")
        let race = ProbeController()
        race.api.failure = APIError.server(code: "conflict", message: "busy")
        race.api.beforeReply = { race.state = active }
        await race.startCall(remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .audio)
        check(race.state == active, "late busy reply cannot overwrite an incoming conversation")
        let stale = ProbeController()
        stale.api.failure = APIError.server(code: "conflict", message: "busy")
        stale.api.beforeReply = { stale.api.currentSessionGeneration = 2 }
        await stale.startCall(remoteUserId: peer, remoteDisplayName: "Peer", mediaKind: .audio)
        check(stale.state == .idle, "late old-session failure cannot restore a failed screen")
        print("PASS: \(count) actual-method admission/reducer contracts; API and permission seams, no native media/UI proof")
    }
}

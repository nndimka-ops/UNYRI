"""B-035: execute production relay policies and guard their native wiring, offline."""
from pathlib import Path
import argparse
import json
import platform
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
KEY = 'A relay is required by your peer-to-peer setting, but this server has no relay configured. The transfer cannot connect safely.'
PEER_KEY = 'The other person allows server-only connections, but the relay server is unavailable.'
CALL_KEY = 'This call needs a relay (TURN), but this server has none. A safe connection is unavailable.'


def source_gates():
    peer = (ROOT / 'ios/UNYRI/DirectTransfer/DirectFilePeer.swift').read_text(encoding='utf-8')
    assert 'iceTransportPolicy = ice.relayOnly ? .relay : .all' in peer
    assert 'throw Failure.relayUnavailable' in peer
    assert peer.index('throw Failure.relayUnavailable') < peer.index('factory = RTCPeerConnectionFactory')
    assert 'ice = try DirectFileIceConfiguration(servers: iceServers.map {' in peer
    assert '}, relayOnly: relayOnly)' in peer
    assert 'configuration.iceServers = ice.servers.map { server in' in peer
    assert 'RTCIceServer(urlStrings: server.urls, username: server.username, credential: server.credential)' in peer
    assert 'selectedRouteAllowed(local: localType, remote: remoteType, relayOnly: relayOnly)' in peer
    assert peer.count('publishedDescription(') == 2
    assert 'publishedCandidate(candidate.sdp, relayOnly: self.relayOnly)' in peer
    assert 'RemoteIcePolicy.filterSDP(sdp, relayOnly: relayOnly)' in peer
    assert 'RemoteIcePolicy.allows(value, relayOnly: relayOnly)' in peer
    files = (ROOT / 'ios/UNYRI/DirectTransfer/DirectFileTransfers.swift').read_text(encoding='utf-8')
    assert 'record.outgoing ? record.offer.recipientUserId : record.offer.senderUserId' in files
    assert 'UnyriDeviceSettings.shared.callPeerToPeer, peerIsContact: isContact(peerUserId)' in files
    assert 'DirectFilePeer(iceServers: servers, relayOnly: relayOnly' in files
    assert 'record.errorCode = "relay_unavailable"' in files
    assert 'reason: "peer_relay_unavailable"' in files
    assert 'DirectFileRecoveryPolicy.receivedPauseReason(control.reason)' in files
    assert 'DirectFileRecoveryPolicy.pauseReasonForPeer(reason)' in files
    assert 'DirectFileRecoveryPolicy.hardStop(record.errorCode)' in files
    byte_admission = files[files.index('private func manualByteEligible('):files.index('private func pauseActive(')]
    assert 'DirectFileRecoveryPolicy.byteEligible(manualEligible: manualIntentEligible(id), reason: record.errorCode)' in byte_admission
    identity_admission = files[files.index('private func receiverIdentityEligible('):files.index('private func receiverRequestEligible(')]
    assert '!hardRecoveryFailure(record)' in identity_admission
    native_admission = files[files.index('private func makePeer('):files.index('private func launchSenderPreparation(')]
    assert '!hardRecoveryFailure(record)' in native_admission and '!hardRecoveryFailure(latest)' in native_admission
    # Only an explicit sender Retry bypasses the reason gate before saving a
    # new retry reason. Automatic admissions must use manualByteEligible.
    assert files.count('manualIntentEligible(') == 3
    retry = files[files.index('func retrySender('):files.index('func cancel(')]
    assert 'guard self.manualIntentEligible(id)' in retry
    assert retry.index('guard self.manualIntentEligible(id)') < retry.index('record.errorCode = "reconnect_required"')
    receiver_retry = files[files.index('private func action('):files.index('private func beginReceiverRequest(')]
    assert receiver_retry.index('record.errorCode = nil') < receiver_retry.index('self.receiverIdentityEligible(id)')
    received_pause = files[files.index('case .pause:\n', files.index('private func applyEffect(')):files.index('case .cancel, .reject:', files.index('private func applyEffect('))]
    assert received_pause.index('if hardRecoveryFailure(record) { break }') < received_pause.index('record.errorCode = DirectFileRecoveryPolicy.receivedPauseReason(')
    models = (ROOT / 'ios/UNYRI/DirectTransfer/DirectFileModels.swift').read_text(encoding='utf-8')
    assert 'DirectFilePeerPolicy.transportCandidate(sdp)' in models
    assert '"relay_unavailable"' in models
    assert '"peer_relay_unavailable"' in models
    calls = (ROOT / 'ios/UNYRI/Calls/CallController.swift').read_text(encoding='utf-8')
    gate = calls.index('guard CallRelayFallbackPolicy.canInitialize(')
    assert gate < calls.index('engine.initialize(', gate)
    assert 'apply(.relayUnavailable(callId: callId))' in calls[gate:calls.index('engine.initialize(', gate)]
    strings = json.loads((ROOT / 'ios/UNYRI/Localizable.xcstrings').read_text(encoding='utf-8'))
    for key in [KEY, PEER_KEY, CALL_KEY]:
        for language in ['ru', 'en', 'uk']:
            assert strings['strings'][key]['localizations'][language]['stringUnit']['state'] == 'translated'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    source_gates()
    if args.generate_only:
        print('PASS: DirectFileRelayContracts source gates (Swift NOT executed)')
        return
    with tempfile.TemporaryDirectory(prefix='unyri-file-relay-') as temp:
        binary = Path(temp) / 'contracts'
        target = ['-target', platform.machine() + '-apple-macos14.2'] if platform.system() == 'Darwin' else []
        subprocess.run(['swiftc', '-swift-version', '5', '-parse-as-library', *target,
                        str(ROOT / 'ios/UNYRI/Calls/CallNetworkPolicy.swift'),
                        str(ROOT / 'ios/UNYRI/DirectTransfer/DirectFilePeerPolicy.swift'),
                        str(HERE / 'main.swift'), '-o', str(binary)], check=True, timeout=180)
        subprocess.run([str(binary)], check=True, timeout=60)


if __name__ == '__main__':
    main()

"""Run actual outgoing admission/controller methods with Foundation API/permission seams."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'runtime-logs/call-busy-banner-20261030/ios-admission-contracts'
OUT.mkdir(parents=True, exist_ok=True)
source = (ROOT / 'ios/UNYRI/Calls/CallController.swift').read_text(encoding='utf-8')


def member(marker):
    start = source.index(marker)
    brace = source.index('{', start)
    end, depth = brace + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


adapter = r'''
import Foundation
enum APIError: Error { case server(code: String, message: String), network(String) }
@MainActor enum CallMediaPermissions {
    struct PermissionError: Error {}
    static var requests = 0
    static var beforeReturn: (() -> Void)?
    static func request(for kind: CallMediaKind) async throws { requests += 1; beforeReturn?() }
}
struct CreatedCall { let id = UUID() }
@MainActor final class CallsRepository {
    enum Action { case cancel }
    var currentSessionGeneration: UInt64 = 1
    var failure: Error?
    var createCount = 0
    var beforeReply: (() -> Void)?
    func create(calleeUserId: UUID, mediaKind: CallMediaKind) async throws -> CreatedCall {
        createCount += 1; beforeReply?()
        if let failure { throw failure }
        return CreatedCall()
    }
    func perform(_ action: Action, callId: UUID) async throws -> CreatedCall { CreatedCall() }
}
@MainActor final class ProbeController {
    var state = CallUiState.idle
    var reducerState = CallReducerState()
    var effects: [CallEffect] = []
    var startBlockedMessage: String?
    var isShutDown = false
    var generation: UInt64 = 1
    var api = CallsRepository()
    var apiGeneration: UInt64 { api.currentSessionGeneration }
    var creatingCall = false
    var externalMediaBusy: () -> Bool = { false }
    var externalCaptureBusy: () -> Bool = { false }
    func requireSession() throws { if isShutDown || generation != apiGeneration { throw CancellationError() } }
    func reconcileAfterReconnect() {}
    func apply(_ event: CallEvent) {
        reducerState.ui = state
        let result = CallStateReducer.reduce(reducerState, event)
        reducerState = result.0; state = result.0.ui; effects = result.1
    }
    __START__
    __BLOCK__
}
'''.replace('__START__', member('func startCall(')).replace('__BLOCK__', member('@discardableResult private func reportBlockedStart('))
(OUT / 'Adapters.swift').write_text(adapter, encoding='utf-8')
exe = OUT / ('contracts.exe' if os.name == 'nt' else 'contracts')
files = [ROOT / name for name in ['ios/UNYRI/Support/AppLanguage.swift', 'ios/UNYRI/Support/HistoryArchive.swift',
    'ios/UNYRI/Calls/CallModels.swift', 'ios/UNYRI/Calls/CallReconciliation.swift', 'ios/UNYRI/Calls/CallStateReducer.swift']]
subprocess.run(['swiftc', '-parse-as-library', *map(str, files), str(OUT / 'Adapters.swift'),
    str(Path(__file__).with_name('main.swift')), '-o', str(exe)], check=True, timeout=100)
subprocess.run([str(exe)], check=True, timeout=30)

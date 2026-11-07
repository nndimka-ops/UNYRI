"""Execute actual CallKit preflight code with permission/transaction seams.

No Apple SDK, call, audio, camera, network, account or signing is accessed.
"""
from pathlib import Path
import hashlib, json, os, subprocess, tempfile

root=Path(__file__).resolve().parents[3]
coordinator=(root/'ios/UNYRI/Calls/CallKitCoordinator.swift').read_text(encoding='utf-8')
controller=(root/'ios/UNYRI/Calls/CallController.swift').read_text(encoding='utf-8')
policy=(root/'ios/UNYRI/Calls/CallKitPolicy.swift').read_text(encoding='utf-8')

def block(source,marker):
    start=source.index(marker);opening=source.index('{',start);end=opening+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]

seams=r'''import Foundation
@MainActor enum CallMediaPermissions {
    static var requests=0
    static var hook:(() async throws -> Void)?
    static func request(for kind:CallMediaKind) async throws {requests+=1;try await hook?()}
    static func reset(){requests=0;hook=nil}
}
final class CXHandle {enum Kind {case generic};init(type:Kind,value:String){}}
final class CXStartCallAction {
    let callUUID:UUID;var isVideo=false;var contactIdentifier:String?
    init(call:UUID,handle:CXHandle){callUUID=call}
}
struct CXTransaction {let action:CXStartCallAction}
@MainActor final class TransactionProbe {
    var completions:[(Error?)->Void]=[]
    func request(_ transaction:CXTransaction,completion:@escaping(Error?)->Void){completions.append(completion)}
}
@MainActor final class ControllerProbe {
    var state=CallUiState.idle
    var generation:UInt64=1
    var creatingCall=false
    var roomBusy=false,recordingBusy=false
    var startBlockedMessage:String?
    func externalMediaBusy()->Bool{roomBusy}
    func externalCaptureBusy()->Bool{recordingBusy}
    __BLOCKED__
    __BRIDGE__
}
@MainActor final class CoordinatorProbe {
    var controller=ControllerProbe()
    var isShutDown=false
    var generation:UInt64=1
    var apiGeneration:UInt64{controller.generation}
    var pendingOutgoing:(uuid:UUID,remoteUserId:String,name:String,isVideo:Bool)?
    var outgoingStart=CallKitOutgoingStartGate()
    var actionError:String?
    let callKit=TransactionProbe()
    __START__
    __ERROR__
}
'''
bridge=block(controller,'@discardableResult func presentBlockedStartIfNeeded(') if 'func presentBlockedStartIfNeeded(' in controller else '@discardableResult func presentBlockedStartIfNeeded()->Bool {reportBlockedStart()}'
seams=seams.replace('__BLOCKED__',block(controller,'@discardableResult private func reportBlockedStart(')).replace('__BRIDGE__',bridge)
seams=seams.replace('__START__',block(coordinator,'func startOutgoing(')).replace('__ERROR__',block(coordinator,'private func reportError('))
seams+='\n'+block(policy,'struct CallKitOutgoingStartGate')
with tempfile.TemporaryDirectory(prefix='coco-callkit-outgoing-') as temp:
    folder=Path(temp);source=folder/'Adapters.swift';source.write_text(seams,encoding='utf-8')
    binary=folder/('contracts.exe' if os.name=='nt' else 'contracts')
    production=['ios/UNYRI/Support/AppLanguage.swift','ios/UNYRI/Support/HistoryArchive.swift','ios/UNYRI/Calls/CallModels.swift']
    subprocess.run(['swiftc','-parse-as-library',*[str(root/n) for n in production],str(source),str(Path(__file__).with_name('main.swift')),'-o',str(binary)],check=True,timeout=100)
    subprocess.run([str(binary)],check=True,timeout=30)

"""Execute real blocked-account model with controlled request/mutation races.
Only Combine observation and the concrete repository conformance are omitted.
"""
from pathlib import Path
import subprocess,tempfile,os
root=Path(__file__).resolve().parents[3]
source=(root/'ios/UNYRI/Users/BlockedAccountsModel.swift').read_text(encoding='utf-8')
source=source.replace('import Combine\n','').replace(': ObservableObject {',' {').replace('@Published ','').replace('extension UsersRepository: BlockedAccountsService {}','')
harness=r'''
import Foundation
@MainActor final class Gate {
 var entered=false;var continuation:CheckedContinuation<Void,Never>?
 func wait() async {entered=true;await withCheckedContinuation{continuation=$0}}
 func open(){continuation?.resume();continuation=nil}
}
enum FixtureError:Error {case failed}
@MainActor final class Service:BlockedAccountsService {
 var blockedAccountScope:String?="owner-session",blockedAccountOwnerID="owner"
 var rows:[PublicUser]=[];var writes=0;var fail=false;var nextCursor:String?;var offset:Int64?
 var readGate:Gate?,writeGate:Gate?
 func blockedAccounts(query:String,cursor:String?) async throws -> BlockedAccountsPage {
  let snapshot=rows
  if let gate=readGate {readGate=nil;await gate.wait()}
  return BlockedAccountsPage(items:snapshot.map{BlockedAccount(user:$0,blockedAt:"2026-10-31")},nextCursor:nextCursor)
 }
 func directory(query:String,limit:Int,offset:Int64) async throws -> SearchResponse {SearchResponse(items:rows,nextOffset:self.offset)}
 func setAccountBlocked(userID:String,blocked:Bool) async throws {
  writes += 1
  if let gate=writeGate {writeGate=nil;await gate.wait()}
  if fail {throw FixtureError.failed}
  if !blocked {rows.removeAll{$0.id==userID}}
 }
}
@main struct Run {
 @MainActor static func main() async throws {
  var checks=0
  func check(_ b:Bool,_ why:String){precondition(b,why);checks += 1}
  let peer=PublicUser(id:"peer",username:"peer",displayName:"Peer",bio:nil,avatarUrl:nil,profileVersion:1,avatarVersion:1,hasAvatar:false)
  let own=PublicUser(id:"owner",username:"owner",displayName:"Owner",bio:nil,avatarUrl:nil,profileVersion:1,avatarVersion:1,hasAvatar:false)
  func wait(_ gate:Gate) async {for _ in 0..<1000{if gate.entered{return};await Task.yield()};preconditionFailure("gate not reached")}
  do {
   let service=Service();service.rows=[peer,peer,own]
   let model=BlockedAccountsModel(service:service);await model.load()
   check(model.users==[peer],"list removes duplicate and own account")
   check(await model.change(peer),"confirmed unblock succeeds")
   check(model.users.isEmpty && !model.mutationFailed && model.changing.isEmpty,"confirmed unblock removes row and releases busy state")
  }
  do {
   let service=Service();service.rows=[peer];service.fail=true
   let model=BlockedAccountsModel(service:service);await model.load()
   check(!(await model.change(peer)),"server failure is not a false unblock success")
   check(model.users==[peer] && model.mutationFailed && model.changing.isEmpty,"failed mutation keeps row for retry")
  }
  do {
   let service=Service();service.rows=[peer];let gate=Gate();service.writeGate=gate
   let model=BlockedAccountsModel(service:service);await model.load()
   let first=Task{await model.change(peer)};await wait(gate)
   check(!(await model.change(peer)) && service.writes==1,"double tap sends only one mutation")
   gate.open();_ = await first.value
  }
  do {
   let service=Service();service.rows=[peer];let gate=Gate();service.writeGate=gate
   let model=BlockedAccountsModel(service:service);await model.load()
   let task=Task{await model.change(peer)};await wait(gate)
   service.blockedAccountScope="new-account";gate.open()
   check(!(await task.value) && model.users.isEmpty && !model.mutationFailed,"late old-account mutation cannot populate successor UI")
  }
  do {
   let service=Service();service.rows=[peer];let model=BlockedAccountsModel(service:service)
   await model.load();let gate=Gate();service.readGate=gate
   let task=Task{await model.load()};await wait(gate)
   service.blockedAccountScope=nil;gate.open();await task.value
   check(model.users.isEmpty && !model.loading,"logout clears in-flight list")
  }
  do {
   let service=Service();service.rows=[peer];service.nextCursor="not-a-valid-cursor"
   let model=BlockedAccountsModel(service:service);await model.load()
   check(model.loadFailed && !model.hasMore && !model.loading,"bad cursor never creates endless load-more")
  }
  do {
   let service=Service();service.rows=[peer];let model=BlockedAccountsModel(service:service,mode:.people)
   await model.load();check(await model.change(peer),"block from directory succeeds")
   await model.load();check(model.users.isEmpty,"newly blocked peer stays excluded from add list")
  }
  do {
   let service=Service();service.rows=[peer];let model=BlockedAccountsModel(service:service)
   await model.load()
   let mutationGate=Gate();service.writeGate=mutationGate
   let mutation=Task{await model.change(peer)};await wait(mutationGate)
   let pageGate=Gate();service.readGate=pageGate
   let reload=Task{await model.load()};await wait(pageGate)
   mutationGate.open();check(await mutation.value,"unblock acknowledged before delayed page")
   check(model.users.isEmpty,"row removed immediately after mutation acknowledgement")
   pageGate.open();await reload.value
   check(model.users.isEmpty && !model.loading,"late page started during unblock must not resurrect the removed row")
  }
  print("Blocked-account actual-model contracts: \(checks) passed; no native/device proof")
 }
}
'''
with tempfile.TemporaryDirectory(prefix='coco-blocked-buttons-') as temp:
 p=Path(temp);model=p/'Model.swift';model.write_text(source,encoding='utf-8');main=p/'main.swift';main.write_text(harness,encoding='utf-8')
 binary=p/('checks.exe' if os.name=='nt' else 'checks')
 subprocess.run(['swiftc','-parse-as-library',str(model),str(root/'ios/UNYRI/Models/UserModels.swift'),str(main),'-o',str(binary)],check=True,timeout=100)
 subprocess.run([str(binary)],check=True,timeout=15)

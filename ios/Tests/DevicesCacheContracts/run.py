"""Execute the real revoke method with network/session/cache boundaries."""
from pathlib import Path
import subprocess,tempfile,os
root=Path(__file__).resolve().parents[3]
source=(root/'ios/UNYRI/UI/Profile/ProfileView.swift').read_text(encoding='utf-8')
start=source.index('    private func revokeDevice(_ id: String) async {')
brace=source.index('{',start);end=brace+1;depth=1
while depth:
 depth+=(source[end]=='{')-(source[end]=='}');end+=1
method=source[start:end].replace('private func','func',1)
support=r'''
import Foundation
struct AccountDevice:Codable,Equatable { let id:String }
enum ProbeFailure:Error {case network}
@MainActor final class Auth {
 var sessionGeneration:UInt64=1
 var cached=[AccountDevice(id:"current"),AccountDevice(id:"other")]
 var writes=0
 var beforeToken:(()->Void)?
 func currentAccessToken() async throws -> String {beforeToken?();return "not-a-live-token"}
 func saveScreen(_ key:String,generation:UInt64,value:[AccountDevice]) {
  precondition(key=="devices");guard generation==sessionGeneration else{return};cached=value;writes+=1
 }
}
@MainActor final class Client {
 var requests=0;var fails=false;var duringDelete:(()->Void)?
 func deleteNoContent(_ path:String,accessToken:String) async throws {
  precondition(path=="/devices/other");requests+=1;duringDelete?();if fails{throw ProbeFailure.network}
 }
}
@MainActor final class Subject {
 var auth=Auth(),client=Client()
 var devices=[AccountDevice(id:"current"),AccountDevice(id:"other")]
 var busy=false;var error:String?
 __METHOD__
}
var count=0,failed=0
func check(_ value:Bool,_ label:String){count+=1;if value{print("PASS: "+label)}else{failed+=1;print("FAIL: "+label)}}
@main struct Run {
 @MainActor static func main() async {
  let success=Subject();await success.revokeDevice("other")
  check(success.devices==[AccountDevice(id:"current")],"success keeps the current device and removes only revoked device")
  check(success.auth.cached==success.devices && success.auth.writes==1,"offline cached active sessions match the confirmed server mutation")
  check(!success.busy && success.error==nil,"success releases controls without an error")
  let failedRequest=Subject();failedRequest.client.fails=true;await failedRequest.revokeDevice("other")
  check(failedRequest.devices.count==2 && failedRequest.auth.cached.count==2 && failedRequest.auth.writes==0,"failed mutation never hides or persists a revoked device")
  check(!failedRequest.busy && failedRequest.error != nil,"failed mutation releases controls and explains failure")
  let pending=Subject();pending.busy=true;await pending.revokeDevice("other")
  check(pending.client.requests==0 && pending.auth.writes==0,"a second tap while busy cannot submit another revocation")
  let changed=Subject();changed.client.duringDelete={changed.auth.sessionGeneration+=1};await changed.revokeDevice("other")
  check(changed.devices.count==2 && changed.auth.writes==0 && !changed.busy,"old response cannot modify the successor session or its cache")
  let early=Subject();early.auth.beforeToken={early.auth.sessionGeneration+=1};await early.revokeDevice("other")
  check(early.client.requests==0 && early.auth.writes==0 && !early.busy,"session replacement before admission sends no old request")
  print("Devices cache actual-method contracts: \(count-failed) passed, \(failed) failed")
  if failed>0{exit(1)}
 }
}
'''.replace('__METHOD__',method)
with tempfile.TemporaryDirectory(prefix='coco-devices-cache-') as temp:
 p=Path(temp);(p/'main.swift').write_text(support,encoding='utf-8')
 binary=p/('contracts.exe' if os.name=='nt' else 'contracts')
 subprocess.run(['swiftc','-parse-as-library',str(p/'main.swift'),'-o',str(binary)],check=True,timeout=100)
 subprocess.run([str(binary)],check=True,timeout=20)

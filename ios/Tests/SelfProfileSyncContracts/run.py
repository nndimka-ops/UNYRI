"""Execute actual self-profile invalidation/cache/coalescing code with controlled HTTP seams."""
from pathlib import Path
import hashlib,json,subprocess,tempfile
root=Path(__file__).resolve().parents[3]
source=(root/'ios/UNYRI/Users/UsersRepository.swift').read_text(encoding='utf-8')
models=(root/'ios/UNYRI/Models/UserModels.swift').read_text(encoding='utf-8')
def block(text,marker):
 start=text.index(marker);end=text.index('{',start)+1;depth=1
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
methods='\n'.join(block(source,marker)for marker in ['    func cachedMe()', '    private func cacheProfile(', '    func getMe()',
 '    private func currentProfileOwner(', '    func refreshOwnProfile(', '    func stopOwnProfileSync()',
 '    private func currentToken()', '    private func requireSession()'])
properties='\n'.join(line.replace('@Published private(set) ','')for line in source.splitlines() if any(line.strip().startswith(prefix)for prefix in [
 'private var profileMutationRevision:', '@Published private(set) var ownProfile:', 'private var profileRequest:',
 'private var profileSyncTask:', 'private var profileSyncID:', 'private var profileHintRevision:',
 'private var wantedProfileVersion:', 'private var ownProfileRetired ']))
seams=r'''
import Foundation
let owner="11111111-1111-4111-8111-111111111111"
let foreign="22222222-2222-4222-8222-222222222222"
@MainActor enum AppConfig {static var storageOrigin="owned-qa-origin"}
@MainActor final class Auth {
 struct User {let id:String}
 var currentUser:User?=User(id:owner);var sessionGeneration:UInt64=1;var saved:UserProfile?
 func currentAccessToken()async throws->String {"owned-fixture"}
 func cachedScreen<T>(_ key:String,as type:T.Type)->T?{saved as? T}
 func saveScreen<T:Encodable>(_ key:String,generation:UInt64,value:T){precondition(generation==sessionGeneration);saved=value as? UserProfile}
}
@MainActor final class API {
 var gets=[CheckedContinuation<UserProfile,Error>]()
 func getMe(accessToken:String)async throws->UserProfile{try await withCheckedThrowingContinuation{gets.append($0)}}
 func finish(_ index:Int,_ profile:UserProfile){gets[index].resume(returning:profile)}
}
@MainActor final class Fixture {
 let auth=Auth();let api=API();let generation:UInt64=1
 let directoryOwner=owner;let directoryOrigin="owned-qa-origin"
__PROPERTIES__
__METHODS__
 func settled()async {await profileSyncTask?.value}
 func seed(_ profile:UserProfile){cacheProfile(profile)}
}
'''.replace('__PROPERTIES__',properties).replace('__METHODS__',methods)
with tempfile.TemporaryDirectory(prefix='coco-own-profile-')as temp:
 path=Path(temp);policy=path/'Policy.swift';exe=path/'contracts.exe'
 policy.write_text(seams+'\n'+block(models,'struct UserProfile:')+'\n'+block(source,'struct SelfProfileInvalidationHint:'),encoding='utf-8')
 subprocess.run(['swiftc','-parse-as-library',str(policy),str(Path(__file__).with_name('main.swift')),'-o',str(exe)],check=True,timeout=90)
 subprocess.run([str(exe)],check=True,timeout=40)

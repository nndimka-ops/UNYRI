"""Actual approval API/control-flow methods with explicit in-memory HTTP seams.
No Apple SDK, live server, native UI or media claim.
"""
from pathlib import Path
import subprocess, tempfile, os
root=Path(__file__).resolve().parents[3]
source=(root/'ios/UNYRI/Conferences/ConferencesAPI.swift').read_text(encoding='utf-8')
models=source[source.index('struct ConferenceJoinRequest:'):source.index('struct ConferenceCapabilities:')]
methods=source[source.index('    static func needsApproval('):source.index('    func create(title:')]
controller=(root/'ios/UNYRI/Conferences/ConferenceController.swift').read_text(encoding='utf-8')
def member(marker):
    start=controller.index(marker);end=controller.index('{',start)+1;depth=1
    while depth:
        depth+=(controller[end]=='{')-(controller[end]=='}');end+=1
    return controller[start:end]
owner_methods='\n'.join(member(m) for m in ['    private func stopJoinApprovalMonitor()', '    private func startJoinApprovalMonitor(', '    func decideJoinRequest('])
owner_methods=owner_methods.replace('private func','func')
harness=r'''
import Foundation
enum UnyriL10n { static func string(_ s: String) -> String { s } }
enum APIError: Error { case missingSession, network(String), http(Int), server(String,String) }
enum ConferenceDirectoryFailure: Error { case invalidHandoff }
struct User { var id: String }
@MainActor final class Auth {
    var currentUser: User? = User(id:"33333333-3333-4333-8333-333333333333")
    var currentDeviceId: UUID? = UUID(uuidString:"44444444-4444-4444-8444-444444444444")
}
__MODELS__
@MainActor final class Client {
    var handle: (String,String,Data?) async throws -> Data = {_,_,_ in Data()}
    let decoder: JSONDecoder = { let d=JSONDecoder();d.keyDecodingStrategy = .convertFromSnakeCase;d.dateDecodingStrategy = .iso8601;return d }()
    func get<T: Decodable>(_ path:String,accessToken:String,as:T.Type) async throws -> T { try decoder.decode(T.self,from:try await handle("GET",path,nil)) }
    func delete<T: Decodable>(_ path:String,accessToken:String,as:T.Type) async throws -> T { try decoder.decode(T.self,from:try await handle("DELETE",path,nil)) }
    func post<B:Encodable,T:Decodable>(_ path:String,accessToken:String,body:B,as:T.Type) async throws -> T {
        let e=JSONEncoder();e.keyEncodingStrategy = .convertToSnakeCase
        return try decoder.decode(T.self,from:try await handle("POST",path,e.encode(body)))
    }
}
@MainActor final class API {
    let client=Client();var auth:Auth?=Auth();var isCurrentSession=true
    func requireCurrent() throws { try Task.checkCancellation();guard isCurrentSession else {throw APIError.missingSession} }
    func token() async throws -> String { try requireCurrent();return "test-token" }
__METHODS__
}
@MainActor final class Owner {
    enum Phase {case connected, reconnecting}
    struct Room {let id:UUID;var approvalRequired:Bool?=true}
    let api:API
    var phase=Phase.connected, isOwner=true, foreground=true, minimized=false, retired=false, joinApprovalAvailable=true
    var conference:Room?, attempt=UUID(), pendingJoinRequests:[ConferenceJoinRequest]=[], decidingJoinRequestID:UUID?, error:String?
    var joinApprovalTask:Task<Void,Never>?,joinDecisionTask:Task<Void,Never>?,approvalWaitTask:Task<Void,Error>?
    var joinApprovalRevision:UInt64=0
    init(_ api:API,_ room:UUID) {self.api=api;conference=Room(id:room)}
    func check(_ ticket:UUID) throws {try api.requireCurrent();guard attempt==ticket && !retired else {throw APIError.missingSession}}
__OWNER_METHODS__
}
@main struct Run {
 @MainActor static func main() async throws {
    var passed=0
    func check(_ b:Bool,_ label:String) { precondition(b,label);passed += 1 }
    let room=UUID(uuidString:"11111111-1111-4111-8111-111111111111")!
    func response(_ api:API,_ id:String,_ status:String,_ override:[String:String]=[:]) throws -> Data {
        var value:[String:Any] = ["request_id":id,"conference_id":room.uuidString.lowercased(),
            "requester_user_id":api.auth!.currentUser!.id,"requester_device_id":api.auth!.currentDeviceId!.uuidString.lowercased(),
            "display_name":"Guest","username":"guest","status":status,"expires_at":"2030-01-01T00:00:00Z"]
        for(k,v) in override {value[k]=v}
        return try JSONSerialization.data(withJSONObject:value)
    }
    func id(_ data:Data?) throws -> String { ((try JSONSerialization.jsonObject(with:data! ) as! [String:Any])["request_id"] as! String).lowercased() }
    for state in ["approved","consumed","not_required"] {
        let api=API();var requests=0
        api.client.handle={method,path,data in requests += 1;precondition(method=="POST");return try response(api,id(data),state)}
        try await api.waitForJoinApproval(room:room,secret:"invitation",password:" room password ",waiting:{preconditionFailure("unexpected waiting")})
        check(requests==1,"accepted decision returns without deleting grant: "+state)
    }
    do {
        let api=API();var saved="";var posts=0;var gets=0;var deletes=0
        api.client.handle={method,path,data in
            if method=="DELETE" {deletes += 1;return Data("{\"ok\":true}".utf8)}
            if method=="POST" {posts += 1;saved=try id(data);throw APIError.network("lost acknowledgement")}
            gets += 1;precondition(path.hasSuffix(saved));return try response(api,saved,"approved")
        }
        try await api.waitForJoinApproval(room:room,secret:nil,password:nil,waiting:{})
        check(posts==1 && gets==1 && deletes==0,"lost POST response recovers exact request without duplicate")
    }
    for state in ["denied","cooldown","expired","cancelled"] {
        let api=API();var deletes=0
        api.client.handle={method,path,data in
            if method=="DELETE" {deletes += 1;return Data("{\"ok\":true}".utf8)}
            return try response(api,id(data),state,["retry_at":"2030-01-01T00:01:00Z"])
        }
        do {try await api.waitForJoinApproval(room:room,secret:nil,password:nil,waiting:{});preconditionFailure("should refuse")}
        catch let failure as ConferenceApprovalFailure {
            if ["denied","cooldown"].contains(state) {guard case .denied(let date)=failure, date != nil else {preconditionFailure("cooldown lost")} }
        }
        check(deletes==1,"terminal "+state+" cleans exact request")
    }
    do {
        let api=API();var saved="";var waits=0;var reads=0
        api.client.handle={method,path,data in
            if method=="POST" {saved=try id(data);return try response(api,saved,"pending")}
            reads += 1;return try response(api,saved,"approved")
        }
        try await api.waitForJoinApproval(room:room,secret:nil,password:nil,waiting:{waits += 1})
        check(waits==1 && reads==1,"pending request is visible before approval")
    }
    do {
        let api=API();var waiting=false;var deleted="";var saved="";var returned=false
        api.client.handle={method,path,data in
            if method=="DELETE" {deleted=path;return Data("{\"ok\":true}".utf8)}
            saved=try id(data);return try response(api,saved,"pending")
        }
        let task=Task { @MainActor in try await api.waitForJoinApproval(room:room,secret:nil,password:nil,waiting:{waiting=true});returned=true }
        while !waiting {try await Task.sleep(nanoseconds:1_000_000)}
        task.cancel();do {try await task.value;preconditionFailure("cancelled returned")} catch is CancellationError {}
        check(!returned && deleted.hasSuffix(saved),"cancel promptly deletes exact waiting ID and never admits")
    }
    do {
        let api=API();var waiting=false;var deletes=0
        api.client.handle={method,path,data in
            if method=="DELETE" {deletes += 1;return Data("{\"ok\":true}".utf8)}
            return try response(api,id(data),"pending")
        }
        let task=Task { @MainActor in try await api.waitForJoinApproval(room:room,secret:nil,password:nil,waiting:{waiting=true}) }
        while !waiting {try await Task.sleep(nanoseconds:1_000_000)}
        api.isCurrentSession=false;task.cancel();_ = try? await task.value
        check(deletes==0,"account replacement never writes using successor session")
    }
    for wrong in ["conference_id","requester_user_id","requester_device_id","request_id","status"] {
        let api=API();var deletes=0
        api.client.handle={method,path,data in
            if method=="DELETE" {deletes += 1;return Data("{\"ok\":true}".utf8)}
            return try response(api,id(data),"approved",[wrong:wrong=="status" ? "unknown" : UUID().uuidString])
        }
        do {try await api.waitForJoinApproval(room:room,secret:nil,password:nil,waiting:{});preconditionFailure("mismatch accepted")}
        catch ConferenceDirectoryFailure.invalidHandoff {}
        check(deletes==1,"rejects mismatched "+wrong)
    }
    do {
        let api=API();var posts=0;var deletes=0
        api.client.handle={method,path,data in
            if method=="DELETE" {deletes += 1;return Data("{\"ok\":true}".utf8)}
            if method=="POST" {posts += 1;throw APIError.http(429)}
            throw APIError.http(404)
        }
        let task=Task { @MainActor in try await api.waitForJoinApproval(room:room,secret:nil,password:nil,waiting:{}) }
        try await Task.sleep(nanoseconds:150_000_000)
        check(posts==1,"429 does not trigger immediate retry storm")
        task.cancel();_ = try? await task.value
        check(deletes==1,"rate-limit wait remains cancellable")
    }
    do {
        let api=API();var posts=0;var readLimited=false
        api.client.handle={method,path,data in
            if method=="DELETE" {return Data("{\"ok\":true}".utf8)}
            if method=="POST" {posts += 1;throw APIError.network("lost acknowledgement")}
            readLimited=true;throw APIError.http(429)
        }
        let task=Task { @MainActor in try await api.waitForJoinApproval(room:room,secret:nil,password:nil,waiting:{}) }
        while !readLimited {try await Task.sleep(nanoseconds:1_000_000)}
        try await Task.sleep(nanoseconds:3_200_000_000)
        check(posts==1,"429 on recovery GET overrides shorter network retry")
        task.cancel();_ = try? await task.value
    }
    check(API.needsApproval(APIError.server("conflict","Conference creator approval is required.")),"exact approval sentinel")
    check(!API.needsApproval(APIError.server("conflict","Conference admission is locked.")),"other conflicts never trigger approval")
    func decoded(_ api:API,_ status:String="pending") throws -> ConferenceJoinRequest {
        try api.client.decoder.decode(ConferenceJoinRequest.self,from:response(api,UUID().uuidString,status))
    }
    func settle(_ owner:Owner) async throws {
        for _ in 0..<1000 {if owner.decidingJoinRequestID==nil{return};try await Task.sleep(nanoseconds:1_000_000)}
        preconditionFailure("bounded owner decision")
    }
    do {
        let api=API(),owner:Owner;owner=Owner(api,room);let request=try decoded(api)
        var writes=0
        api.client.handle={method,path,data in writes += 1;return try response(api,request.id.uuidString,"approved")}
        owner.pendingJoinRequests=[request];owner.isOwner=false;owner.decideJoinRequest(request,approve:true)
        check(writes==0 && owner.decidingJoinRequestID==nil,"non-creator cannot submit decision")
        owner.isOwner=true;owner.decideJoinRequest(request,approve:true);try await settle(owner)
        check(writes==1 && owner.pendingJoinRequests.isEmpty,"accepted creator decision removes exact request")
    }
    do {
        let api=API(),owner:Owner;owner=Owner(api,room);let request=try decoded(api)
        owner.pendingJoinRequests=[request]
        api.client.handle={_,_,_ in throw APIError.network("lost response")}
        owner.decideJoinRequest(request,approve:false);try await settle(owner)
        check(owner.error != nil && owner.pendingJoinRequests==[request],"failed decision keeps row for safe retry")
    }
    do {
        let api=API(),owner:Owner;owner=Owner(api,room);let request=try decoded(api)
        var called=false;var release:CheckedContinuation<Void,Never>?
        owner.pendingJoinRequests=[request]
        api.client.handle={_,_,_ in called=true;await withCheckedContinuation{release=$0};return try response(api,request.id.uuidString,"approved")}
        owner.decideJoinRequest(request,approve:true)
        while !called {try await Task.sleep(nanoseconds:1_000_000)}
        owner.attempt=UUID();owner.stopJoinApprovalMonitor();release?.resume()
        try await Task.sleep(nanoseconds:10_000_000)
        check(owner.pendingJoinRequests.isEmpty && owner.error==nil && owner.decidingJoinRequestID==nil,"late decision cannot modify a successor room")
    }
    do {
        let api=API(),owner:Owner;owner=Owner(api,room);let request=try decoded(api);var reads=0
        api.client.handle={_,_,_ in
            reads += 1;let record=try JSONSerialization.jsonObject(with:response(api,request.id.uuidString,"pending"))
            return try JSONSerialization.data(withJSONObject:["items":[record]])
        }
        owner.startJoinApprovalMonitor(owner.attempt)
        for _ in 0..<1000 {if !owner.pendingJoinRequests.isEmpty{break};try await Task.sleep(nanoseconds:1_000_000)}
        check(reads==1 && owner.pendingJoinRequests==[request],"creator receives bounded in-room pending queue")
        owner.stopJoinApprovalMonitor();let count=reads
        owner.foreground=false;owner.startJoinApprovalMonitor(owner.attempt)
        try await Task.sleep(nanoseconds:20_000_000);owner.stopJoinApprovalMonitor()
        check(reads==count,"background queue watcher does not make requests")
    }
    print("Conference join approval actual-method contracts: \(passed) passed; no native UI/media proof")
 }
}
'''.replace('__MODELS__',models).replace('__METHODS__',methods).replace('__OWNER_METHODS__',owner_methods)
with tempfile.TemporaryDirectory(prefix='coco-approval-') as temp:
    folder=Path(temp);p=folder/'main.swift';p.write_text(harness,encoding='utf-8')
    binary=folder/('contracts.exe' if os.name=='nt' else 'contracts')
    subprocess.run(['swiftc','-parse-as-library',str(p),'-o',str(binary)],check=True,timeout=100)
    subprocess.run([str(binary)],check=True,timeout=25)

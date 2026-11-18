import Foundation

@main struct OutgoingAdmissionContracts {
    @MainActor static func main() async {
        var passed=0,failures:[String]=[]
        func check(_ value:Bool,_ label:String){if value{passed+=1;print("PASS: "+label)}else{failures.append(label);print("FAIL: "+label)}}
        func start(_ p:CoordinatorProbe){p.startOutgoing(remoteUserId:UUID().uuidString,displayName:"Synthetic",isVideo:false)}
        func drain() async {for _ in 0..<40 {await Task.yield()}}
        let roomMessage="You are already in a room. Leave it before starting another conversation."
        CallMediaPermissions.reset()
        let room=CoordinatorProbe();room.controller.roomBusy=true;start(room);await drain()
        check(room.controller.startBlockedMessage==roomMessage,"room busy is explained before permission or CallKit")
        check(CallMediaPermissions.requests==0 && room.callKit.completions.isEmpty,"room busy starts no permission or transaction")
        CallMediaPermissions.reset()
        let capture=CoordinatorProbe();capture.controller.recordingBusy=true;start(capture);await drain()
        check(capture.controller.startBlockedMessage=="Finish the current recording before starting a call.","recording busy is explained")
        check(CallMediaPermissions.requests==0 && capture.pendingOutgoing==nil,"recording busy owns no outgoing attempt")
        CallMediaPermissions.reset()
        let late=CoordinatorProbe();CallMediaPermissions.hook={late.controller.roomBusy=true};start(late);await drain()
        check(late.pendingOutgoing==nil && late.outgoingStart.activeUUID==nil,"room acquired during permission releases only its pending attempt")
        check(late.controller.startBlockedMessage==roomMessage && late.callKit.completions.isEmpty,"late busy is explained without submitting CallKit")
        late.controller.roomBusy=false;CallMediaPermissions.hook=nil;start(late);await drain()
        check(late.callKit.completions.count==1 && CallMediaPermissions.requests==2,"a busy permission race can retry without restarting app")
        check(late.pendingOutgoing != nil && late.outgoingStart.activeUUID==late.pendingOutgoing?.uuid,"successful submission retains ownership until provider action")
        CallMediaPermissions.reset()
        let incoming=CoordinatorProbe()
        let info=CallInfo(callId:UUID().uuidString,remoteUserId:UUID().uuidString,remoteDisplayName:"Incoming",mediaKind:.audio,direction:.incoming)
        CallMediaPermissions.hook={incoming.controller.state = .incomingRinging(info)};start(incoming);await drain()
        check(incoming.pendingOutgoing==nil && incoming.outgoingStart.activeUUID==nil,"incoming during permissions releases outgoing ownership")
        check(incoming.controller.state == .incomingRinging(info) && incoming.callKit.completions.isEmpty,"outgoing never replaces a newly arrived incoming call")
        check(incoming.controller.startBlockedMessage != nil,"newly active call is explained")
        CallMediaPermissions.reset()
        let denied=CoordinatorProbe();CallMediaPermissions.hook={throw NSError(domain:"SyntheticPermission",code:1)};start(denied);await drain()
        check(denied.pendingOutgoing==nil && denied.outgoingStart.activeUUID==nil,"denial releases pending and gate")
        check(denied.actionError != nil && denied.callKit.completions.isEmpty,"current permission denial is reported without transaction")
        CallMediaPermissions.reset()
        let stale=CoordinatorProbe();CallMediaPermissions.hook={stale.controller.generation+=1};start(stale);await drain()
        check(stale.pendingOutgoing==nil && stale.outgoingStart.activeUUID==nil,"session replacement clears stale preflight ownership")
        check(stale.actionError==nil && stale.controller.startBlockedMessage==nil && stale.callKit.completions.isEmpty,"old permission success cannot affect new session")
        CallMediaPermissions.reset()
        let staleFailure=CoordinatorProbe();CallMediaPermissions.hook={staleFailure.controller.generation+=1;throw NSError(domain:"SyntheticOldPermission",code:2)}
        start(staleFailure);await drain()
        check(staleFailure.pendingOutgoing==nil && staleFailure.outgoingStart.activeUUID==nil,"old permission failure clears its own attempt")
        check(staleFailure.actionError==nil,"old permission failure cannot publish a successor-session error")
        CallMediaPermissions.reset()
        let duplicate=CoordinatorProbe();start(duplicate);start(duplicate);await drain()
        check(CallMediaPermissions.requests==1 && duplicate.callKit.completions.count==1,"double tap submits exactly one transaction")
        let old=duplicate.pendingOutgoing!.uuid,new=UUID()
        duplicate.outgoingStart.finish(old);_ = duplicate.outgoingStart.begin(new)
        duplicate.pendingOutgoing=(new,"successor","Successor",false)
        duplicate.callKit.completions[0](NSError(domain:"SyntheticLateTransaction",code:3));await drain()
        check(duplicate.pendingOutgoing?.uuid==new && duplicate.outgoingStart.activeUUID==new,"late transaction failure preserves successor ownership")
        check(duplicate.actionError==nil,"late old transaction failure is not shown for successor attempt")
        CallMediaPermissions.reset()
        let stopped=CoordinatorProbe();stopped.isShutDown=true;start(stopped);await drain()
        check(stopped.pendingOutgoing==nil && CallMediaPermissions.requests==0,"retired coordinator starts nothing")
        print("CallKit outgoing actual-method contracts: \(passed) passed, \(failures.count) failed; no Apple SDK/media/network proof")
        if !failures.isEmpty {exit(1)}
    }
}

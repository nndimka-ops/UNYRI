import Foundation

func profile(_ version:Int64,_ name:String="Current",id:String=owner,avatar:Int64=0)->UserProfile {
 UserProfile(id:id,username:"ownqa",displayName:name,bio:"Own biography",avatarUrl:nil,profileVersion:version,
  avatarVersion:avatar,hasAvatar:avatar>0,createdAt:"2026-11-01",updatedAt:"2026-11-01",adminRole:"owner")
}
@main struct SelfProfileChecks {
 @MainActor static func wait(_ fixture:Fixture,_ count:Int)async {
  let deadline=Date().addingTimeInterval(4)
  while fixture.api.gets.count<count {precondition(Date()<deadline,"Controlled GET did not start");await Task.yield()}
  precondition(fixture.api.gets.count==count,"Unexpected extra HTTP request")
 }
 @MainActor static func main()async throws {
  let valid=Data("{\"type\":\"profile.changed\",\"user_id\":\"\(owner)\",\"profile_version\":2}".utf8)
  precondition(SelfProfileInvalidationHint.parse(valid)==SelfProfileInvalidationHint(userID:owner,version:2))
  for bad in ["{}","{\"type\":\"appearance.changed\",\"user_id\":\"\(owner)\",\"profile_version\":2}",
   "{\"type\":\"profile.changed\",\"user_id\":\"invalid\",\"profile_version\":2}",
   "{\"type\":\"profile.changed\",\"user_id\":\"\(owner)\",\"profile_version\":-1}",
   "{\"type\":\"profile.changed\",\"user_id\":\"\(owner)\",\"profile_version\":1.5}",
   "{\"type\":\"profile.changed\",\"user_id\":\"\(owner)\",\"profile_version\":\"2\"}",
   "{\"type\":\"profile.changed\",\"user_id\":\"\(owner)\",\"profile_version\":true}"] {
    precondition(SelfProfileInvalidationHint.parse(Data(bad.utf8))==nil)
  }
  print("PASS: strict UUID/version profile hint; malformed/negative/fraction/string/bool/unknown frames ignored")

  let burst=Fixture()
  for _ in 0..<10 {burst.refreshOwnProfile(SelfProfileInvalidationHint(userID:owner,version:2))}
  await wait(burst,1);burst.api.finish(0,profile(2));await burst.settled()
  precondition(burst.ownProfile?.profileVersion==2 && burst.auth.saved?.adminRole==nil && burst.ownProfile?.adminRole=="owner")
  precondition(burst.auth.sessionGeneration==1)
  for hint in [SelfProfileInvalidationHint(userID:owner,version:2),.init(userID:owner,version:1),.init(userID:foreign,version:100)]{burst.refreshOwnProfile(hint)}
  for _ in 0..<100{await Task.yield()};precondition(burst.api.gets.count==1)
  print("PASS: ten hints coalesce; duplicate/older/foreign hints ignored; role stripped only from offline cache; auth epoch unchanged")

  let concurrent=Fixture();let first=Task{try await concurrent.getMe()};let second=Task{try await concurrent.getMe()}
  await wait(concurrent,1);first.cancel();concurrent.api.finish(0,profile(1))
  do{_=try await first.value;preconditionFailure("Cancelled caller must not succeed")}catch is CancellationError{}
  _=try await second.value;precondition(concurrent.api.gets.count==1 && concurrent.ownProfile?.profileVersion==1)
  let cancelled=Fixture();let only=Task{try await cancelled.getMe()};await wait(cancelled,1);only.cancel();cancelled.api.finish(0,profile(1))
  do{_=try await only.value;preconditionFailure()}catch is CancellationError{}
  precondition(cancelled.ownProfile==nil && cancelled.auth.saved==nil)
  print("PASS: shared GET single flight; cancelling one caller preserves another; cancelled-only read publishes nothing")

  let beforeHint=Fixture();let old=Task{try await beforeHint.getMe()};await wait(beforeHint,1)
  beforeHint.refreshOwnProfile(.init(userID:owner,version:2));beforeHint.api.finish(0,profile(1));_=try await old.value
  await wait(beforeHint,2);beforeHint.api.finish(1,profile(2));await beforeHint.settled()
  precondition(beforeHint.ownProfile?.profileVersion==2)
  print("PASS: a UI GET predating the hint is drained, followed by a fresh canonical read")

  let advancing=Fixture();advancing.refreshOwnProfile(.init(userID:owner,version:2));await wait(advancing,1)
  advancing.refreshOwnProfile(.init(userID:owner,version:3));advancing.api.finish(0,profile(2));await wait(advancing,2)
  advancing.api.finish(1,profile(3));await advancing.settled();precondition(advancing.ownProfile?.profileVersion==3)
  advancing.refreshOwnProfile(.init(userID:owner,version:5));await wait(advancing,3)
  advancing.api.finish(2,profile(4));await advancing.settled();precondition(advancing.api.gets.count==3,"Lagging response must not start polling")
  advancing.refreshOwnProfile();await wait(advancing,4);advancing.api.finish(3,profile(5));await advancing.settled()
  let stale=Task{try await advancing.getMe()};await wait(advancing,5);advancing.api.finish(4,profile(2,"Obsolete"))
  let retained=try await stale.value;precondition(retained.profileVersion==5 && advancing.ownProfile?.profileVersion==5)
  advancing.seed(profile(6,avatar:3))
  let oldAvatar=Task{try await advancing.getMe()};await wait(advancing,6);advancing.api.finish(5,profile(7,avatar:2))
  let keptAvatar=try await oldAvatar.value;precondition(keptAvatar.avatarVersion==3 && advancing.ownProfile?.profileVersion==6)
  print("PASS: newer in-flight hint gets one follow-up; lagging server is not polled; foreground catches up; older GET cannot roll back UI")

  let mixed=Fixture();mixed.seed(profile(1))
  mixed.refreshOwnProfile(.init(userID:owner,version:2));await wait(mixed,1)
  mixed.refreshOwnProfile(.init(userID:owner,version:10))
  mixed.refreshOwnProfile();mixed.refreshOwnProfile(.init(userID:owner,version:4))
  mixed.api.finish(0,profile(2));await wait(mixed,2)
  // A forced foreground/reconnect is not a replacement for the newer desired
  // revision. With a lagging second response, this real trigger gets one more
  // read; neither nil nor the lower hint may erase the wanted version 10.
  mixed.refreshOwnProfile();mixed.api.finish(1,profile(4));await wait(mixed,3)
  mixed.api.finish(2,profile(10));await mixed.settled()
  precondition(mixed.ownProfile?.profileVersion==10 && mixed.api.gets.count==3)
  print("PASS: interleaved foreground/reconnect and lower hints preserve the maximum wanted version")

  for changed in 0..<3 {
   let f=Fixture();let operation=Task{try await f.getMe()};await wait(f,1)
   if changed==0{f.auth.sessionGeneration+=1};if changed==1{AppConfig.storageOrigin="different-origin"};if changed==2{f.auth.currentUser=Auth.User(id:foreign)}
   f.api.finish(0,profile(2))
   do{_=try await operation.value;preconditionFailure("Old namespace accepted a profile")}catch is CancellationError{}
   precondition(f.ownProfile==nil && f.auth.saved==nil);AppConfig.storageOrigin="owned-qa-origin"
  }
  let retired=Fixture();retired.refreshOwnProfile();await wait(retired,1);retired.stopOwnProfileSync();retired.api.finish(0,profile(2))
  for _ in 0..<100{await Task.yield()};retired.refreshOwnProfile(.init(userID:owner,version:3))
  for _ in 0..<100{await Task.yield()};precondition(retired.ownProfile==nil && retired.auth.saved==nil && retired.api.gets.count==1)
  let wrong=Fixture();let wrongTask=Task{try await wrong.getMe()};await wait(wrong,1);wrong.api.finish(0,profile(2,id:foreign))
  do{_=try await wrongTask.value;preconditionFailure()}catch is CancellationError{}
  precondition(wrong.ownProfile==nil && wrong.auth.saved==nil)
  let malformed=Fixture();let negative=Task{try await malformed.getMe()};await wait(malformed,1);malformed.api.finish(0,profile(-1))
  do{_=try await negative.value;preconditionFailure()}catch is CancellationError{}
  precondition(malformed.ownProfile==nil && malformed.auth.saved==nil)
  print("PASS: account/origin/epoch changes, retired service and foreign response reject every late publication")
  print("Self-profile sync contracts passed; controlled API/storage, no native UI or real socket claim")
 }
}

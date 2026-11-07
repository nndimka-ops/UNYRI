"""Execute actual chat placement/geometry source; a command recorder is not a native SwiftUI simulation."""
from pathlib import Path
import subprocess,hashlib,json,re,argparse,shutil
parser=argparse.ArgumentParser()
parser.add_argument("--project-root",type=Path,default=Path(__file__).resolve().parents[3])
parser.add_argument("--source-chat",type=Path)
parser.add_argument("--baseline-chat",type=Path)
parser.add_argument("--swiftc",default=shutil.which("swiftc") or "swiftc")
parser.add_argument("--output",type=Path,required=True)
args=parser.parse_args()
ROOT=args.output
ROOT.mkdir(parents=True,exist_ok=True)
MAIN=args.project_root
SWIFT=Path(args.swiftc)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def extract(s,marker):
 start=s.index(marker);brace=s.index('{',start);end=brace+1;depth=1
 while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[start:end]
support=(MAIN/'ios/UNYRI/Support/ChatViewportPolicy.swift').read_text(encoding='utf-8')
mosaic=(MAIN/'ios/UNYRI/Support/AlbumMosaicLayout.swift').read_text(encoding='utf-8')
anchor=extract(mosaic,'enum UnyriTimelineAnchor {')

HEAD=r'''
import Foundation
protocol PreferenceKey { associatedtype Value;static var defaultValue:Value { get };static func reduce(value:inout Value,nextValue:()->Value) }
struct UnitPoint { let value:String;static let top=UnitPoint(value:"top");static let bottom=UnitPoint(value:"bottom") }
final class Commands { var values:[(String,String)]=[] }
struct ScrollViewProxy {let commands:Commands;func scrollTo(_ id:String,anchor:UnitPoint){commands.values.append((id,anchor.value))}}
struct FixtureMessage {let id:String}
struct TimelineItem {let id:String;var members:[FixtureMessage] { [FixtureMessage(id:id)] }}
struct FixtureChat {let chatId="chat"}
final class FixtureRepository {
 var loadedHistoryChatId:String?="chat"
 var bookmark:(anchor:String?,followsLatest:Bool)?=("B",false)
 func scrollBookmark(chatId:String)->(anchor:String?,followsLatest:Bool)? {bookmark}
}
final class Harness {
 let chat=FixtureChat();let messagesRepository=FixtureRepository()
 var currentBottomGeometryContext="v638";var bottomGeometryIdentity:String?
 var bottomViewportSize=CGSize(width:402,height:638)
 var measuredGeometry=ChatMeasuredGeometry();var didPositionInitialHistory=false;var pendingScrollTarget:String?
 var conversationVisible=true;var keyboardResizing=false;var isNearBottom=false;var newMessagesCount=0
 var keyboardOpeningFollow=ChatKeyboardOpeningFollow()
 var timelineItems=[TimelineItem(id:"A"),TimelineItem(id:"B")]
 let commands=Commands();var proxy:ScrollViewProxy {ScrollViewProxy(commands:commands)}
 METHODS
 func prepare(bottom:Bool=true,frames:Bool=true,target:Bool=false) {
  if bottom {bottomGeometryIdentity=currentBottomGeometryContext;measuredGeometry.bottom=ChatBottomFrames(content:CGRect(x:0,y:0,width:402,height:2000),end:nil,viewport:bottomViewportSize)}
  if frames {measuredGeometry.itemsIdentity=currentBottomGeometryContext;measuredGeometry.items=["A":CGRect(x:0,y:0,width:300,height:100)]}
  if target {measuredGeometry.items["B"]=CGRect(x:0,y:120,width:300,height:200)}
 }
 func invoke(){restoreScroll(proxy,items:timelineItems)}
}
func require(_ value:@autoclosure()->Bool,_ label:String){if !value(){fputs("FAIL \(label)\n",stderr);exit(1)}}
'''

GREEN=r'''
let early=Harness();early.invoke()
require(early.commands.values.isEmpty && !early.didPositionInitialHistory,"initialcommand must wait for coherent current geometry")
early.prepare(bottom:true,frames:false);early.invoke();require(early.commands.values.isEmpty,"bottom alone not enough")
let onlyFrames=Harness();onlyFrames.prepare(bottom:false);onlyFrames.invoke();require(onlyFrames.commands.values.isEmpty,"frames alone not enough")
early.prepare();early.measuredGeometry.itemsIdentity="old";early.invoke();require(early.commands.values.isEmpty,"stale frame context")
early.measuredGeometry.itemsIdentity=early.currentBottomGeometryContext;early.invoke()
require(early.commands.values.count==1 && early.commands.values[0].0=="B" && early.commands.values[0].1=="top","admit actual resolved row once")
require(early.pendingScrollTarget=="B" && early.didPositionInitialHistory,"issued means positioned, not visible acknowledgement")
require(early.measuredGeometry.items["B"]==nil,"offscreen target frame is not admission prerequisite")
for _ in 0..<100 {early.invoke()};require(early.commands.values.count==1,"no perframe repeat")
early.applyBottomPosition(identity:early.currentBottomGeometryContext);require(early.pendingScrollTarget=="B","missing target not acknowledged")
early.measuredGeometry.items["B"]=CGRect(x:0,y:120,width:300,height:200);early.measuredGeometry.itemsIdentity="old"
early.applyBottomPosition(identity:early.currentBottomGeometryContext);require(early.pendingScrollTarget=="B","old context target cannot ACK")
early.measuredGeometry.itemsIdentity=early.currentBottomGeometryContext
early.applyBottomPosition(identity:early.currentBottomGeometryContext);require(early.pendingScrollTarget==nil,"actual current intersecting frame ACK")
let alreadyVisible=Harness();alreadyVisible.prepare(target:true);alreadyVisible.invoke();alreadyVisible.applyBottomPosition(identity:alreadyVisible.currentBottomGeometryContext)
require(alreadyVisible.commands.values.count==1 && alreadyVisible.pendingScrollTarget==nil,"already visible reader row")
for follows in [false,true] {
 let end=Harness();end.messagesRepository.bookmark=follows ? ("B",true):nil;end.prepare()
 end.measuredGeometry.bottom=ChatBottomFrames(content:CGRect(x:0,y:0,width:402,height:500),end:nil,viewport:end.bottomViewportSize)
 end.invoke();require(end.commands.values.count==1 && end.commands.values[0].0=="end" && end.commands.values[0].1=="bottom","no bookmark/latest target")
 end.applyBottomPosition(identity:end.currentBottomGeometryContext);require(end.pendingScrollTarget==nil,"short content end ACK")
}
let mismatch=Harness();mismatch.prepare();mismatch.messagesRepository.loadedHistoryChatId="other";mismatch.invoke();require(mismatch.commands.values.isEmpty,"history scope")
let empty=Harness();empty.prepare();empty.timelineItems=[];empty.invoke();require(empty.commands.values.isEmpty,"empty waits")
empty.timelineItems=[TimelineItem(id:"A"),TimelineItem(id:"B")];empty.invoke();require(empty.commands.values.count==1,"nonempty current initial arrival")
for mode in 0..<4 {
 let invalid=Harness();invalid.prepare()
 switch mode {
 case 0:invalid.conversationVisible=false
 case 1:invalid.keyboardResizing=true
 case 2:invalid.bottomViewportSize = .zero
 default:invalid.measuredGeometry.bottom=ChatBottomFrames(content:CGRect(x:0,y:0,width:402,height:0),end:nil,viewport:invalid.bottomViewportSize)
 }
 invalid.invoke();require(invalid.commands.values.isEmpty,"visibility/keyboard/viewport/content guard")
}
let resized=Harness();resized.prepare();resized.invoke();resized.currentBottomGeometryContext="v500";resized.bottomViewportSize.height=500;resized.prepare()
for _ in 0..<20 {resized.invoke()};require(resized.commands.values.count==1,"no automatic viewport/arrival retry budget")
let pan=Harness();pan.cancelInitialPositionForUserIntent();pan.prepare();pan.invoke()
require(pan.commands.values.isEmpty && pan.didPositionInitialHistory && pan.pendingScrollTarget==nil,"explicit user pan/jump replaces waiting intent")
let afterPan=Harness();afterPan.prepare();afterPan.invoke();afterPan.cancelInitialPositionForUserIntent();afterPan.currentBottomGeometryContext="new";afterPan.prepare();afterPan.invoke()
require(afterPan.commands.values.count==1 && afterPan.pendingScrollTarget==nil,"no late automatic jump after explicit interaction")
let brokenAnchor=Harness();brokenAnchor.messagesRepository.bookmark=("missing",false);brokenAnchor.prepare();brokenAnchor.invoke()
require(brokenAnchor.commands.values.count==1 && brokenAnchor.commands.values[0].0=="end","preserve unresolved canonical fallback")

let rect=CGRect(x:0,y:40,width:90,height:20)
var first=ChatItemFrames.defaultValue;require(first.frames.isEmpty && first.context==nil,"actual preference default")
ChatItemFrames.reduce(value:&first,nextValue:{ChatItemFrameBatch(frames:["A":rect])})
ChatItemFrames.reduce(value:&first,nextValue:{ChatItemFrameBatch(frames:["B":rect])})
require(first.frames.count==2,"actual preference reduce merges rows")
tag(&first,bottomIdentity:"v734")
var settled=first;tag(&settled,bottomIdentity:"v638")
require(first.frames==settled.frames && first != settled,"unchanged CGRect/new viewport still changes actual preference value")
require(settled.context=="v638","parent tags actual current pass")
require(capture0(presentationID:"A",inAlbum:true,item:TimelineItem(id:"B"),geometry:Geometry()).frames.isEmpty,"inner album does not emit row frames")
require(capture0(presentationID:"A",inAlbum:false,item:TimelineItem(id:"B"),geometry:Geometry()).frames["A"] != nil,"actual singleton emission")
require(capture1(presentationID:"A",inAlbum:false,item:TimelineItem(id:"B"),geometry:Geometry()).frames["B"] != nil,"actual outer album emission")
require(capture2(presentationID:"A",inAlbum:false,item:TimelineItem(id:"B"),geometry:Geometry()).frames["B"] != nil,"actual call emission")
print("PASS actual-source admission/one-command/ACK/pan/reader/end/context/default/reduce/all3emission contracts")
print("LIMIT command recorder measures emitted commands only; does not simulate or prove native SwiftUI behavior")
'''

results=[]
inputs=([('before',args.baseline_chat)] if args.baseline_chat else [])+[('after',args.source_chat or MAIN/'ios/UNYRI/UI/Chats/ChatScreen.swift')]
for label,path in inputs:
 s=path.read_text(encoding='utf-8');folder=ROOT/'tests'/label;folder.mkdir(parents=True,exist_ok=True)
 methods='\n'.join(extract(s,marker).replace('private func','func',1) for marker in ['    private func measuredBottomPosition(', '    private func applyBottomPosition(', '    private func restoreScroll(', '    private func timelineRowID('])
 methods=methods.replace('conversationEndID','"end"')
 if label=='after':methods+='\n'+extract(s,'    private func cancelInitialPositionForUserIntent(').replace('private func','func',1)
 base='import Foundation\n#if canImport(CoreGraphics)\nimport CoreGraphics\n#endif\n'+support+'\n'+anchor+'\n'+extract(s,'private final class ChatMeasuredGeometry {').replace('private final','final',1)+'\n'+extract(s,'private struct ChatBottomFrames:').replace('private struct','struct',1)+'\n'+HEAD.replace('METHODS',methods)
 if label=='before':
  tail='''let early=Harness();early.invoke();if !early.commands.values.isEmpty {print("EXPECTED FAIL native-observed admission contract: actual035 restore emits a command before coherent viewport/content/item batch");exit(19)};fatalError("baseline unexpectedly satisfies admission contract")'''
 else:
  batch=extract(s,'private struct ChatItemFrameBatch:').replace('private struct','struct',1)
  key=extract(s,'private struct ChatItemFrames:').replace('private struct','struct',1)
  tagging=extract(s,'.transformPreference(ChatItemFrames.self) {')[extract(s,'.transformPreference(ChatItemFrames.self) {').index('{')+1:-1].removeprefix(' batch in')
  expressions=re.findall(r'value: (ChatItemFrameBatch[^\r\n]+)',s)
  assert len(expressions)==3
  capture='''struct Space {static func named(_ s:String)->Space{Space()}}\nstruct Geometry {func frame(in space:Space)->CGRect{CGRect(x:0,y:0,width:80,height:40)}}\n'''
  capture+='\n'.join('func capture'+str(i)+'(presentationID:String,inAlbum:Bool,item:TimelineItem,geometry:Geometry)->ChatItemFrameBatch { '+ 'return '+v[:-1]+' }' for i,v in enumerate(expressions))
  base+='\n'+batch+'\n'+key+'\nfunc tag(_ batch:inout ChatItemFrameBatch,bottomIdentity:String){'+tagging+'}\n'+capture
  tail=GREEN
 source=folder/'main.swift';source.write_text(base+'\n'+tail,encoding='utf-8')
 binary=folder/'contracts.exe'
 compiled=subprocess.run([str(SWIFT),'-swift-version','5',str(source),'-o',str(binary)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
 (folder/'compile.log').write_text(compiled.stdout+'\n'+compiled.stderr,encoding='utf-8')
 assert compiled.returncode==0,compiled.stderr
 executed=subprocess.run([str(binary)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
 (folder/'execution.log').write_text(executed.stdout+'\n'+executed.stderr,encoding='utf-8')
 assert executed.returncode==(19 if label=='before' else 0),executed.stdout+executed.stderr
 results.append({'label':label,'actual_source_sha256':sha(path),'harness_sha256':sha(source),'compile_exit':compiled.returncode,'execution_exit':executed.returncode,'output':executed.stdout.strip()})
print(json.dumps(results,indent=2));(ROOT/'tests/RESULTS.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')

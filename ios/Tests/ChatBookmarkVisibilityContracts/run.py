"""Execute the timeline's actual reading-position row choice; not a native scrolling simulation.

The rule lives in ChatScreen.handleHostedVisibility: the bookmark is the topmost row with more
than 1 pt on screen. Both expressions are compiled verbatim from ChatScreen.swift.
"""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess
parser=argparse.ArgumentParser()
parser.add_argument('--project-root',type=Path,default=Path(__file__).resolve().parents[3])
parser.add_argument('--source-chat',type=Path)
parser.add_argument('--swiftc',default=shutil.which('swiftc') or 'swiftc')
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
path=args.source_chat or args.project_root/'ios/UNYRI/UI/Chats/ChatScreen.swift'
source=path.read_text(encoding='utf-8')
handler=source[source.index('    private func handleHostedVisibility('):]
filter_start=handler.index('        let onScreen = visibility.rows.filter {')
on_screen=handler[filter_start:handler.index('\n',filter_start)]
choice_start=handler.index('onScreen.min(by: ',handler.index('if let first = '))
depth=0
for end in range(choice_start,len(handler)):
    depth+=(handler[end]=='(')-(handler[end]==')')
    if handler[end]==')' and depth==0: break
choice=handler[choice_start:end+1]
actual=on_screen+'\n    return '+choice+'?.id\n'
prefix='''import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif
struct ChatTimelineVisibleRow { let id: String; let frame: CGRect }
struct Visibility { let rows: [ChatTimelineVisibleRow] }
func choose(_ frames:[String:CGRect],bounds:CGRect)->String? {
    let visibility=Visibility(rows:frames.map { ChatTimelineVisibleRow(id:$0.key,frame:$0.value) })
'''
suffix='''}
func require(_ condition:@autoclosure()->Bool,_ label:String) {
    if !condition() {fputs("FAIL \\(label)\\n",stderr);exit(1)}
}
let bounds=CGRect(x:0,y:0,width:402,height:638)
let captured=["previous":CGRect(x:0,y:-360.1532846715329,width:386,height:360.25618918558996),
              "target":CGRect(x:0,y:0.1029045140570588,width:386,height:360.25618918558985)]
require(choose(captured,bounds:bounds)=="target","native captured subpixel previous row must not become new bookmark")
for overlap in [0.0,0.1,0.3333333333,0.999999,1.0] {
 let rows=["previous":CGRect(x:0,y:overlap-360,width:386,height:360),"target":CGRect(x:0,y:overlap,width:386,height:360)]
 require(choose(rows,bounds:bounds)=="target","at most1pt visible sliver excluded")
}
for overlap in [1.0001,2.0,20.0,200.0] {
 let rows=["previous":CGRect(x:0,y:overlap-360,width:386,height:360),"target":CGRect(x:0,y:overlap,width:386,height:360)]
 require(choose(rows,bounds:bounds)=="previous","meaningful partially visible older row preserved")
}
require(choose(["tall":CGRect(x:0,y:-2000,width:386,height:2500)],bounds:bounds)=="tall","middle of tall album stays bookmarkable")
require(choose(["thin":CGRect(x:0,y:-360,width:386,height:360.1)],bounds:bounds)==nil,"only a sliver does not replace prior bookmark")
require(choose([:],bounds:bounds)==nil,"no rows reported")
require(choose(["outside":CGRect(x:0,y:800,width:386,height:40)],bounds:bounds)==nil,"offscreen ignored")
print("PASS actual bookmark choice: subpixel/exact1pt exclusion, >1pt partial and tall albums retained, empty/outside")
print("LIMIT Foundation row-choice policy; native repeated close/reopen is the scrolling proof")
'''
args.output.mkdir(parents=True,exist_ok=True)
swift=args.output/'main.swift';swift.write_text(prefix+actual+suffix,encoding='utf-8')
binary=args.output/('contracts.exe' if __import__('os').name=='nt' else 'contracts')
compiled=subprocess.run([args.swiftc,str(swift),'-o',str(binary)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
(args.output/'compile.log').write_text(compiled.stdout+'\n'+compiled.stderr,encoding='utf-8');assert compiled.returncode==0,compiled.stderr
result=subprocess.run([str(binary)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15)
(args.output/'execution.log').write_text(result.stdout+'\n'+result.stderr,encoding='utf-8')
proof={'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'actual_choice_sha256':hashlib.sha256(actual.encode()).hexdigest(),
 'compile_exit':compiled.returncode,'execution_exit':result.returncode,'native_scrolling_simulated':False,
 'expected_outcome':result.returncode==0,'output':result.stdout.strip()}
(args.output/'result.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8');print(json.dumps(proof,indent=2));assert proof['expected_outcome']

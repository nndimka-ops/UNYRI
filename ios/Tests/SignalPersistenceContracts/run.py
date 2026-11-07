"""Native checked-Signal regression contracts using actual bundled libsignal.
Requires macOS/Xcode and an already-built arm64 Simulator LibSignalClient
module/object, its SignalFfi headers/archive and an explicitly owned booted
Simulator. Only SecItem I/O is doubled; no real Keychain, account or network.
Example:
  python3 run.py --products BUILD/Debug-iphonesimulator \
    --ffi-module ios/LibSignalClient/Sources/SignalFfi \
    --ffi-library ios/libsignal-ffi/libsignal_ffi.a \
    --simulator OWN-UDID --output NEW-OWN-TEMP-DIRECTORY
No app installation, device creation, boot/reset, signing or server is done.
"""
from pathlib import Path
import argparse,hashlib,json,subprocess,sys
parser=argparse.ArgumentParser(description=__doc__)
for name in ["products","ffi-module","ffi-library","output"]:parser.add_argument("--"+name,type=Path,required=True)
parser.add_argument("--simulator",required=True)
a=parser.parse_args()
if sys.platform!="darwin":raise SystemExit("Requires the owning Apple SDK environment")
repo=Path(__file__).resolve().parents[3];out=a.output.resolve()
assert not out.exists(),"Output must be a new owned temporary directory"
assert (a.products/"LibSignalClient.o").is_file() and (a.ffi_module/"module.modulemap").is_file() and a.ffi_library.is_file()
out.mkdir(parents=True);sha=lambda b:hashlib.sha256(b).hexdigest()
def method(text,marker):
 start=text.index(marker);end=text.index("{",start)+1;depth=1
 while depth:
  depth+=(text[end]=="{")-(text[end]=="}");end+=1
 return text[start:end]
files={};pins={}
for name in ["SignalStateStore.swift","UnyriSignalStores.swift","SignalMetadataStore.swift","SignalStorageKeys.swift"]:
 raw=(repo/"ios/UNYRI/Signal"/name).read_bytes();text=raw.decode("utf-8")
 if name=="SignalStateStore.swift":
  for symbol in ["SecItemCopyMatching","SecItemUpdate","SecItemAdd","SecItemDelete"]:text=text.replace(symbol+"(","Own"+symbol+"(")
 data=text.encode("utf-8");(out/name).write_bytes(data);files[name]=sha(data);pins[name]=sha(raw)
for name in ["Models/SignalModels.swift","Support/Base64.swift","Signal/SignalKeyBootstrap.swift"]:
 raw=(repo/"ios/COCO"/name).read_bytes();key=Path(name).name;(out/key).write_bytes(raw);files[key]=sha(raw);pins[key]=sha(raw)
methods={}
for name,markers,properties in [
 ("SignalSessionManager.swift",["    init(container:","    func encrypt(","    private static func messageType(","    func decrypt("],"    private let container: UnyriSignalStoreContainer\n    private let keysRepository: KeysRepository\n"),
 ("SignalKeyManager.swift",["    init(container:","    func ensureIdentity(","    func generateOneTimePreKeys("],"    private let container: UnyriSignalStoreContainer\n    private let keysRepository: KeysRepository\n    private var stateStore: SignalStateStore { container.stateStore }\n    private var metadata: SignalMetadataStore { container.metadataStore }\n    private var context: UnyriStoreContext { container.context }\n")]:
 raw=(repo/"ios/UNYRI/Signal"/name).read_bytes();text=raw.decode("utf-8");body=[method(text,m)for m in markers]
 data=("import Foundation\nimport LibSignalClient\n@MainActor\nfinal class "+name[:-6]+" {\n"+properties+"\n\n".join(body)+"\n}\n").encode()
 key="Actual"+name;(out/key).write_bytes(data);files[key]=sha(data);pins[name]=sha(raw);methods[name]=[sha(b.encode())for b in body]
text=(repo/"ios/UNYRI/Signal/SignalBundleDecoder.swift").read_text(encoding="utf-8")
data=("import Foundation\n"+method(text,"struct SignalRecipientDevice: Equatable")+"\n").encode()
(out/"ActualSignalRecipient.swift").write_bytes(data);files["ActualSignalRecipient.swift"]=sha(data)
host=Path(__file__).with_name("main.swift").read_bytes();(out/"main.swift").write_bytes(host);files["main.swift"]=sha(host)

auth=(repo/"ios/UNYRI/Auth/SessionStore.swift").read_text(encoding="utf-8")
markers=["    private struct Snapshot:", "    private enum Key {", "    private func read(", "    private func snapshot(",
 "    func userId()", "    func installationId()", "    func durableInstallationId()", "    func checkedServerContinuityUserID()"]
body=[method(auth,m)for m in markers]
data=("import Foundation\nfinal class SessionStore {\n    enum PersistenceError: Error { case failed }\n    private let readValue: (String) throws -> String?\n    private let write: (String, String) -> Bool\n    init(read: @escaping (String) throws -> String?, write: @escaping (String, String) -> Bool) { self.readValue = read; self.write = write }\n"+"\n\n".join(body)+"\n}\n").encode()
(out/"ActualAuthSessionMethods.swift").write_bytes(data);files["ActualAuthSessionMethods.swift"]=sha(data)
pins["Auth/SessionStore.swift"]=sha((repo/"ios/UNYRI/Auth/SessionStore.swift").read_bytes());methods["AuthSessionStore"]=[sha(b.encode())for b in body]
data=(repo/"ios/UNYRI/Models/AuthModels.swift").read_bytes();(out/"AuthModels.swift").write_bytes(data);files["AuthModels.swift"]=sha(data)

manifest={"compiledFiles":files,"actualSourcePins":pins,"exactMethods":methods,"onlySecItemBoundaryDoubled":True}
(out/"SOURCE_MANIFEST.json").write_text(json.dumps(manifest,indent=2))
sdk=subprocess.check_output(["xcrun","--sdk","iphonesimulator","--show-sdk-path"],text=True).strip()
binary=out/"contracts"
command=["xcrun","swiftc","-swift-version","5","-parse-as-library","-target","arm64-apple-ios16.0-simulator","-sdk",sdk,
 "-module-cache-path",str(out/"ModuleCache"),"-I",str(a.products),"-I",str(a.ffi_module),"-L",str(a.ffi_library.parent),
 "-framework","Security","-framework","Foundation","-lc++","-lz"]
command += [str(out/n) for n in files]+[str(a.products/"LibSignalClient.o"),str(a.ffi_library),"-o",str(binary)]
with(out/"compile.log").open("w")as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=240)
if r.returncode:raise SystemExit(r.returncode)
with(out/"run.log").open("w")as log:r=subprocess.run(["xcrun","simctl","spawn",a.simulator,str(binary),str(out/"RESULT.json")],stdout=log,stderr=subprocess.STDOUT,timeout=120)
receipt={"compileExit":0,"runExit":r.returncode,"sourceExactAfter":all(sha((out/n).read_bytes())==h for n,h in files.items()),
 "realSecItemOrNetwork":False,"physicalKeychainOrFPS":False}
(out/"RUN_RECEIPT.json").write_text(json.dumps(receipt,indent=2))
if(out/"RESULT.json").exists():print((out/"RESULT.json").read_text())
print(json.dumps(receipt));raise SystemExit(r.returncode)

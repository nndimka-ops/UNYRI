"""Foundation contracts for the production share store and extracted picker/inbox.

UIKit/SwiftUI presentation, security scopes and iOS file protection need Mac/device
QA. Extraction replaces only those native boundaries. The copy's one injected
after-write hook makes Close during a chunk deterministic; its algorithm and
cancellation checks are otherwise production code.
"""
from pathlib import Path
import json
import os
import platform
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def declaration(source, anchor):
    start = source.index(anchor)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


store = read("ios/Shared/UnyriShareStore.swift")
viewer = read("ios/UNYRI/UI/Chats/UnyriMediaViewer.swift")
picker = read("ios/UNYRI/UI/Chats/UnyriChatPicker.swift")
controller = read("ios/UNYRIShare/LegacyShareViewController.swift")
auth = read("ios/UNYRI/Auth/AuthRepository.swift")
app = read("ios/UNYRI/UNYRIApp.swift")
session = read("ios/UNYRI/Auth/SessionStore.swift")
registry = read("ios/UNYRI/Auth/AccountRegistry.swift")
image = read("ios/UNYRI/Attachments/ImageProcessor.swift")
catalog = json.loads(read("ios/UNYRI/Localizable.xcstrings"))["strings"]

# These connect the Foundation contracts to the native call sites we cannot build
# on Windows. They also catch accidental network/credential sharing regressions.
sheet = declaration(viewer, "struct UnyriImportFileSheet")
assert "await chats.loadCachedChatsAsync()" in sheet and "listChats(" not in sheet
assert 'else if UnyriShareStore.isImage(file.url.lastPathComponent)' in sheet
assert "ImageProcessor.shareSendKind(sourceURL: copy)" in sheet and "queueVisualMedia" in sheet
assert sheet.index("ImageProcessor.shareSendKind") < sheet.index("try await inbox.acknowledge")
assert "maxPreservedImageBytes" in image and "let preserve = preservesOriginal(source)" in image
assert "otherUser == nil" not in declaration(picker, "struct UnyriChatPickerSheet")
closing = declaration(controller, "@objc private func close()")
assert "stagingRequest?.cancellation.cancel()" in closing
assert closing.index("cancelAndRemove()") < closing.index("cancelRequest(")
assert "cancellation: cancellation" in controller
assert "request.commit(entries: entries)" in controller and "UnyriShareStore.activeBinding()" in controller
assert "await extensionIO.prepareExtensionShare(cancellation: preparationCancellation)" in controller
assert 'InboxIO(label: "unyri.share.extension.io")' in controller
assert "preparation.readyBatches < UnyriShareStore.maxReadyBatches" in controller
assert "UnyriShareStore.sweep(" not in controller and "UnyriShareStore.importableReadyBatchCount(" not in controller
assert "UnyriShareStore.root()" not in controller
assert "preparationCancellation?.cancel()" in closing
extension_start = controller[controller.index("let preparation = try await"):]
assert extension_start.index("try Task.checkCancellation()") < extension_start.index("preparation.makeRequest(")
assert extension_start.index("guard !cancelled") < extension_start.index("preparation.makeRequest(")
extension_setup = declaration(store, "func prepareExtensionShare(")
assert "try await perform" in extension_setup and "UnyriShareStore.sweep(" in extension_setup
assert "UnyriShareStore.importableReadyBatchCount(" in extension_setup and extension_setup.count("cancellation.check()") >= 4
assert "StagingRequest(" not in extension_setup
request_start = declaration(store, "func makeRequest(")
assert request_start.index("cancellation.check()") < request_start.index("StagingRequest(")
assert "withIntermediateDirectories: true" not in declaration(store, "final class StagingRequest")
assert "countError; save.isEnabled = false" in declaration(controller, "override func viewDidLoad()")
assert "invalidateShareInboxAccount()" in declaration(auth, "private func beginSessionChange()")
assert "close(discardBatch: false)" in declaration(auth, "private func beginSessionChange()")
publication = declaration(auth, "func syncShareInboxAccount()")
assert "await UnyriShareStore.inboxIO.prepareAccount" in publication
assert publication.index("self.shareInboxScope == scope") < publication.index("publishActiveBinding(binding)")
assert "incomingFile.close(discardBatch: false)" in app
assert "auth.sessionGeneration == services.sessionGeneration" in app
assert 'SHA256.hash(data: Data("unyri.share.v1|' in store
assert "await UnyriShareStore.inboxIO.purgeAllAccounts()" in declaration(session, "static func erase() async throws")
assert "await UnyriShareStore.inboxIO.purgeAccount(" in declaration(registry, "static func erase(_ account:")
assert "purgeAccount" not in declaration(auth, "func switchAccount(")
assert "finishSignOut(ending)" in declaration(auth, "private func finishLocalSession()")
assert "LocalAccountCachePurger.erase()" in declaration(registry, "func finishSignOut(")
assert "UnyriAccountPurger.erase(" in declaration(registry, "func removeInactive(")
assert 'dropping legacy batch' not in viewer
assert "await io.adoptLegacy" in declaration(viewer, "func importLegacy(")
assert "offerLegacy: false" in app
erasure = declaration(store, "static func purgeAllAccounts(root:")
assert 'directory.pathExtension == "staging"' in erasure
assert erasure.index("removeIfPresent(directory)") < erasure.index("try sweep(")
inbox = declaration(viewer, "@MainActor final class UnyriIncomingFile")
assert "FileManager" not in inbox and "Data().write" not in inbox and "UnyriShareStore.root()" not in inbox
assert "UnyriShareStore.sweep" not in inbox and "UnyriShareStore.recoverAdoptions" not in inbox
discovery = declaration(inbox, "func checkSharedInbox(")
assert "await io.discover" in discovery
assert discovery.index("await io.discover") < discovery.index("self.valid(scope, revision: ticket, current: current)") < discovery.index("self.files =")
assert "discoveryNeedsRescan = true" in declaration(discovery, "if let discovery")
assert discovery.index("if self.discoveryNeedsRescan { continue }") < discovery.index("guard case .success") < discovery.index("self.files =")
assert "discoveryNeedsRescan = false" in declaration(inbox, "private func invalidateDiscovery()")
assert "self.scope == scope && current()" in inbox
assert "auth?.shareInboxScope == scope" in app
account_view = declaration(app, "struct AccountContentView:")
unlock = declaration(account_view, ".onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification))")
assert "checkSharedInbox()" in unlock
assert "generation: sessionGeneration" in auth
executor = declaration(store, "final class InboxIO")
assert "DispatchQueue(label:" in executor and "queue.async" in executor
assert "dispatchPrecondition(condition: .notOnQueue(.main))" in executor
assert "queue.sync" not in executor and "Semaphore" not in executor

triples = re.findall(r'\btext\(\s*"([^"\\]*)"\s*,\s*"([^"\\]*)"\s*,\s*"([^"\\]*)"\s*\)', controller)
assert len(triples) >= 17
for en, ru, uk in triples:
    for lang, expected in zip(("en", "ru", "uk"), (en, ru, uk)):
        assert catalog[en]["localizations"][lang]["stringUnit"]["value"] == expected, (en, lang)
for key in ("Chat", "Shared items from before the update: %d", "Shared items from before the update", "Import",
            "Discard shared items", "Could not recover all shared items. Remaining items have been kept. Try again or discard them.",
            "Could not remove shared items. Try again.",
            "Import these items into the current account, or discard them from this device. Nothing is sent until you choose a chat and confirm.",
            "Photos are sent as photos; animated images over 25 MB are sent as files. Videos are compressed before sending."):
    assert all(catalog[key]["localizations"][lang]["stringUnit"]["value"] for lang in ("en", "ru", "uk"))
import plistlib
manifest = plistlib.loads((ROOT / "ios/UNYRIShare/PrivacyInfo.xcprivacy").read_bytes())
assert any(item["NSPrivacyAccessedAPIType"] == "NSPrivacyAccessedAPICategoryDiskSpace" and
           "E174.1" in item["NSPrivacyAccessedAPITypeReasons"] for item in manifest["NSPrivacyAccessedAPITypes"])
print("Share phase 0 source/localization/privacy contracts passed", flush=True)

inbox = declaration(viewer, "@MainActor final class UnyriIncomingFile")
inbox = inbox.replace(": ObservableObject", "").replace("@Published ", "")
snapshot_marker = "let snapshot = try await io.discover(accountKey: scope.accountKey)"
assert inbox.count(snapshot_marker) == 1
# Pause the actual model after the actual store read, without changing its state
# machine, to reproduce a commit/refresh before publication deterministically.
inbox = inbox.replace(snapshot_marker, snapshot_marker + "\n                    if let hook = shareDiscoverySnapshotHook { await hook(snapshot) }")
methods = "\n".join(declaration(picker, anchor) for anchor in (
    "private func name(", "private var filtered:", "private var selectedChats:"))
fixture = read("ios/Tests/SharePhase0Contracts/NativeBoundaries.swift")
fixture = fixture.replace("// PICKER_METHODS", methods).replace("// INBOX_MODEL", inbox)
hook = "try output.write(contentsOf: bytes)"
assert store.count(hook) == 1
store = store.replace(hook, hook + "\n            shareCopyChunkHook?()")
swiftc = shutil.which("swiftc")
if not swiftc and os.name == "nt":
    candidate = Path(os.environ["LOCALAPPDATA"]) / "Programs/Swift/Toolchains/6.3.3+Asserts/usr/bin/swiftc.exe"
    if candidate.exists():
        swiftc = str(candidate)
if not swiftc:
    raise SystemExit("swiftc not found")
compiler = [swiftc, "-swift-version", "5", "-parse-as-library"]
if platform.system() == "Darwin":
    # The production file uses UTType (macOS 11+) and CryptoKit. A command-line
    # compiler's older default deployment target must not hide those contracts.
    compiler += ["-target", platform.machine() + "-apple-macosx13.0"]
with tempfile.TemporaryDirectory(prefix="unyri-share-contracts-") as folder:
    path = Path(folder)
    (path / "Store.swift").write_text(store, encoding="utf-8")
    (path / "NativeBoundaries.swift").write_text(fixture, encoding="utf-8")
    binary = path / ("contracts.exe" if os.name == "nt" else "contracts")
    subprocess.run(compiler + [str(ROOT / "ios/UNYRI/Support/DisplayNameSanitizer.swift"), str(path / "Store.swift"),
                    str(path / "NativeBoundaries.swift"), str(ROOT / "ios/Tests/SharePhase0Contracts/main.swift"),
                    "-o", str(binary)], check=True, timeout=120)
    subprocess.run([str(binary)], check=True, timeout=30)
    if platform.system() == "Darwin":
        images = path / "image-contracts"
        subprocess.run(compiler + [str(path / "Store.swift"), str(ROOT / "ios/UNYRI/Attachments/ImageProcessor.swift"),
                                   str(ROOT / "ios/Tests/SharePhase0Contracts/NativeImages.swift"), "-o", str(images)],
                       check=True, timeout=120)
        subprocess.run([str(images)], check=True, timeout=30)

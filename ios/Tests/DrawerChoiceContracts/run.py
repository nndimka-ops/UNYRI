"""Compile production value policies/codec and the real device store with a Combine publication stand-in.

This does not prove SwiftUI rendering, environment delivery, UIKit gestures or CPU.
"""
from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from ios_portable_swift import _block

source = (ROOT / "ios/UNYRI/UI/Theme/SidebarAppearanceRoot.swift").read_text(encoding="utf-8")
snapshot = _block(source, "private struct UnyriSidebarAppearanceSnapshot")
store = _block(source, "@MainActor\nfinal class UnyriSidebarAppearanceStore")
standins = """import Foundation
protocol ObservableObject: AnyObject {}
@propertyWrapper struct Published<Value> {
    var wrappedValue: Value
    init(wrappedValue: Value) { self.wrappedValue = wrappedValue }
}
"""
with tempfile.TemporaryDirectory(prefix="unyri-drawer-choice-") as directory:
    folder = Path(directory)
    extracted = folder / "Store.swift"
    extracted.write_text(standins + snapshot + "\n" + store, encoding="utf-8")
    sources = [ROOT / "ios/UNYRI/Support/SavedSidebarStyle.swift",
               ROOT / "ios/UNYRI/Support/SidebarAppearanceSettings.swift",
               ROOT / "ios/UNYRI/Support/ContactsPresentation.swift", extracted,
               Path(__file__).with_name("main.swift")]
    binary = folder / ("checks.exe" if os.name == "nt" else "checks")
    subprocess.run(["swiftc", "-parse-as-library", *map(str, sources), "-o", str(binary)], check=True, timeout=180)
    subprocess.run([str(binary)], check=True, timeout=30)

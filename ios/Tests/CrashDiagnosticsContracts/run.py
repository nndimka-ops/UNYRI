from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='coco-crash-contracts-') as d:
 exe=Path(d)/('contracts.exe' if os.name=='nt' else 'contracts')
 subprocess.run(['swiftc','-parse-as-library',str(root/'ios/UNYRI/Support/CrashDiagnostics.swift'),str(Path(__file__).with_name('main.swift')),'-o',str(exe)],check=True,timeout=100)
 subprocess.run([str(exe)],check=True,timeout=30)

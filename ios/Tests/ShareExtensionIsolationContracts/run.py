"""Source, Xcode membership, localization and native-boundary contracts."""
from pathlib import Path
import subprocess

subprocess.run(["node", str(Path(__file__).with_name("check.mjs"))], check=True)

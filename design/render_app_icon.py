"""Export the user-approved UNYRI master to the existing iOS AppIcon slot.

Pillow is required. Technical project and asset names remain upgrade-compatible.
"""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
master = root / "design/branding/unyri/icon-01-refined-r1.png"
with Image.open(master) as image:
    image.convert("RGB").resize((1024, 1024), Image.Resampling.LANCZOS).save(
        root / "ios/UNYRI/Assets.xcassets/AppIcon.appiconset/AppIcon.png", optimize=True
    )

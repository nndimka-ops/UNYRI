"""Export the approved mark for Windows and embedded server pages (Pillow).

Run from any directory. No image generation, geometry changes or external fonts.
The historic SVG filenames remain compatible with existing project references.
"""
from pathlib import Path
from io import BytesIO
import base64
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
MASTER = Path(__file__).with_name("icon-01-refined-r1.png")


def export():
    with Image.open(MASTER) as source:
        image = source.convert("RGB")
        small = image.resize((256, 256), Image.Resampling.LANCZOS)
        buffer = BytesIO()
        small.save(buffer, format="PNG", optimize=True)
        png = buffer.getvalue()
        (ROOT / "server/legal/brand-icon.png").write_bytes(png)
        icons = ROOT / "desktop/assets"
        icons.mkdir(parents=True, exist_ok=True)
        small.save(icons / "unyri.ico", format="ICO",
                   sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    encoded = base64.b64encode(png).decode("ascii")
    lines = ['"' + encoded[i:i + 100] + '"' for i in range(0, len(encoded), 100)]
    data = (
        "// Generated native-size export of design/branding/unyri/icon-01-refined-r1.png.\n"
        "// Preserve this geometry; use the approved master to regenerate.\n"
        "namespace CocoAdmin\n{\n    internal static class BrandIconData\n    {\n"
        "        internal const string PngBase64 =\n            "
        + "\n            + ".join(lines) + ";\n    }\n}\n"
    )
    (ROOT / "desktop/CocoAdminConsole/BrandIconData.cs").write_bytes(data.encode("utf-8"))
    master_data = base64.b64encode(MASTER.read_bytes()).decode("ascii")
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
        'width="1024" height="1024" viewBox="0 0 1024 1024" role="img" aria-label="UNYRI">'
        '<image width="1024" height="1024" xlink:href="data:image/png;base64,'
        + master_data + '"/></svg>\n'
    )
    for name in ("coco-mark.svg", "coco-app-icon.svg"):
        (ROOT / "design" / name).write_bytes(svg.encode("utf-8"))


if __name__ == "__main__":
    export()

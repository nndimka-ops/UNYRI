#!/usr/bin/env python3
"""Package the approved UNYRI master for Android, without redrawing its geometry.

Requires Python 3 and Pillow 12.3.0 for reproducible PNG bytes. No network or SDK.
The default output is this repository. --output-root stages exports elsewhere.
Only the explicit generated PNG paths below are written; source/XML is not edited.
"""
from pathlib import Path
from PIL import Image, ImageDraw, __version__ as pillow_version
import argparse, hashlib, json, math

MASTER_SHA256 = '098f9d1749c0d641ec25a57b71cdfa1a63f1d12637f95a009a2b0d7db596dcc4'
DENSITIES = (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4))
LANCZOS = Image.Resampling.LANCZOS

def sha(data):
    return hashlib.sha256(data).hexdigest()

def radius(image):
    """Farthest corner of every nonzero-alpha pixel from the canvas center."""
    width, height = image.size
    pixels = image.getchannel('A')
    return max((math.hypot(abs(x + .5 - width / 2) + .5,
                           abs(y + .5 - height / 2) + .5)
                for y in range(height) for x in range(width)
                if pixels.getpixel((x, y)) != 0), default=0)

def black_matte_foreground(master):
    """Invert black compositing: A=max(R,G,B), RGB=source*255/A.

    Source values <=3/255 are black-matte raster noise, made fully transparent.
    No path tracing, morphing, stroke/gap edits or independent part transforms.
    On black, RGB foreground reconstructs source RGB to <=3/255 per channel.
    Themed/notification assets use the SAME alpha with strictly white RGB.
    Legacy launcher and in-app assets instead retain the full source RGB image.
    """
    pixels = []
    for red, green, blue in master.getdata():
        alpha = max(red, green, blue)
        if alpha <= 3:
            pixels.append((0, 0, 0, 0))
        else:
            pixels.append((round(red * 255 / alpha), round(green * 255 / alpha),
                           round(blue * 255 / alpha), alpha))
    result = Image.new('RGBA', master.size)
    result.putdata(pixels)
    return result

def white_alpha(image):
    result = Image.new('RGBA', image.size, (255, 255, 255, 0))
    result.putalpha(image.getchannel('A'))
    return result

def adaptive_foreground(foreground, density):
    edge = round(108 * density)
    # Keep the entire glyph, including antialiased pixel corners, inside the
    # centered 66dp-diameter safe circle. Start with a 31.5dp source radius;
    # the final check accounts for resampler support at EACH density.
    initial_scale = 31.5 / (radius(foreground) * 108 / foreground.width)
    ink_edge = round(edge * initial_scale)
    while True:
        image = Image.new('RGBA', (edge, edge), (0, 0, 0, 0))
        scaled = foreground.resize((ink_edge, ink_edge), LANCZOS)
        offset = ((edge - ink_edge) // 2, (edge - ink_edge) // 2)
        image.paste(scaled, offset)
        if radius(image) <= 33 * density:
            return image
        ink_edge -= 1
        if ink_edge < edge // 2:
            raise RuntimeError('Unexpected master silhouette: safe circle cannot be met')

def notification_mask(foreground, density):
    edge = round(24 * density)
    ink = white_alpha(foreground).crop(foreground.getchannel('A').getbbox())
    ink.thumbnail((round(20 * density), round(20 * density)), LANCZOS)
    image = Image.new('RGBA', (edge, edge), (255, 255, 255, 0))
    image.paste(ink, ((edge - ink.width) // 2, (edge - ink.height) // 2))
    return white_alpha(image)

def round_launcher(master, edge):
    image = master.resize((edge, edge), LANCZOS).convert('RGBA')
    mask = Image.new('L', (edge * 4, edge * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, edge * 4 - 1, edge * 4 - 1), fill=255)
    image.putalpha(mask.resize((edge, edge), LANCZOS))
    return image

def export(master_path, output_root):
    if pillow_version != '12.3.0':
        raise RuntimeError('Reproducible export requires Pillow 12.3.0')
    raw = master_path.read_bytes()
    if sha(raw) != MASTER_SHA256:
        raise RuntimeError('Approved master SHA-256 mismatch')
    master = Image.open(master_path)
    if master.mode != 'RGB' or master.size != (1254, 1254):
        raise RuntimeError('Approved master must be 1254x1254 RGB')
    foreground = black_matte_foreground(master)
    files = {}
    def save(relative, image):
        path = output_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path, format='PNG', optimize=False, compress_level=9)
        files[relative] = {'bytes': path.stat().st_size, 'sha256': sha(path.read_bytes()),
                           'mode': image.mode, 'width': image.width, 'height': image.height}
    res = 'android/app/src/main/res/'
    circles = {}
    for name, density in DENSITIES:
        edge = round(48 * density)
        save(res + f'mipmap-{name}/ic_launcher.png', master.resize((edge, edge), LANCZOS))
        save(res + f'mipmap-{name}/ic_launcher_round.png', round_launcher(master, edge))
        adaptive = adaptive_foreground(foreground, density)
        circles[name] = {'nonzero_alpha_pixel_corner_radius_dp': radius(adaptive) / density,
                         'limit_radius_dp': 33, 'canvas_dp': 108}
        save(res + f'drawable-{name}/unyri_launcher_foreground.png', adaptive)
        save(res + f'drawable-{name}/unyri_launcher_monochrome.png', white_alpha(adaptive))
        save(res + f'drawable-{name}/unyri_notification_mask.png', notification_mask(foreground, density))
    save(res + 'drawable-nodpi/unyri_brand_mark.png', master.resize((512, 512), LANCZOS))
    return {'master_sha256': MASTER_SHA256, 'pillow': pillow_version,
            'derivation': 'RGB resize for legacy/in-app; black-matte inverse for adaptive; same silhouette white-alpha for themed/notification; round alpha only for legacy round packaging',
            'adaptive_safe_circle': circles, 'files': files}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    repo = Path(__file__).resolve().parents[3]
    parser.add_argument('--master', type=Path, default=repo / 'design/branding/unyri/icon-01-refined-r1.png')
    parser.add_argument('--output-root', type=Path, default=repo)
    parser.add_argument('--manifest', type=Path, help='Optional receipt path outside application resources')
    args = parser.parse_args()
    result = export(args.master.resolve(), args.output_root.resolve())
    if args.manifest:
        args.manifest.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'master_sha256': result['master_sha256'], 'generated_files': len(result['files']),
                      'all_alpha_pixel_corners_inside_66dp_circle': True}, indent=2))

if __name__ == '__main__':
    main()

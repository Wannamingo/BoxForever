"""Build Android launcher, themed and library assets from the approved BoxForever SVG."""
from io import BytesIO
from math import hypot
from pathlib import Path

import subprocess
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'android/app/src/main/res'
MASTER = ROOT / 'android/artwork/boxforever-cube.svg'
INK = '#121815'
SIGNAL = '#A7F052'
DENSITIES = [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]
ANDROID = 'http://schemas.android.com/apk/res/android'

def write_file(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')

def centered_mask(mask, size, radius):
    """Center the source, scaling its furthest opaque point into a radius in pixels."""
    left, top, right, bottom = mask.getbbox()
    mask = mask.crop((left, top, right, bottom))
    # An enclosing radius computed from the ends of each scanline lets an isometric
    # silhouette occupy more of the launcher than its rectangular bounding box.
    radius_src = 0
    for y in range(mask.height):
        row = mask.crop((0, y, mask.width, y + 1)).getbbox()
        if row:
            dx = max(abs(row[0] - mask.width / 2), abs(row[2] - mask.width / 2))
            radius_src = max(radius_src, hypot(dx, y + 0.5 - mask.height / 2))
    scale = radius / radius_src
    fitted = mask.resize((round(mask.width * scale), round(mask.height * scale)), Image.Resampling.LANCZOS)
    result = Image.new('L', (size, size))
    result.paste(fitted, ((size - fitted.width) // 2, (size - fitted.height) // 2))
    return result

def tint(mask, color):
    layer = Image.new('RGBA', mask.size, color)
    layer.putalpha(mask)
    return layer

def tile(mask, size, radius):
    scale = 4
    side = size * scale
    base = Image.new('RGBA', (side, side))
    ImageDraw.Draw(base).rounded_rectangle((scale, scale, side - scale - 1, side - scale - 1),
                                          radius=round(side * 0.2), fill=INK)
    base.alpha_composite(tint(centered_mask(mask, side, radius * scale), SIGNAL))
    return base.resize((size, size), Image.Resampling.LANCZOS)

def adaptive_descriptor(themed):
    mono = '  <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n' if themed else ''
    return (f'<adaptive-icon xmlns:android="{ANDROID}">\n'
            '  <background android:drawable="@color/ic_launcher_background"/>\n'
            '  <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
            f'{mono}</adaptive-icon>\n')

def main():
    encoded = subprocess.check_output(['inkscape', str(MASTER), '--export-type=png',
                                       '--export-width=1400', '--export-height=1400',
                                       '--export-filename=-'])
    master = Image.open(BytesIO(encoded)).convert('RGBA').getchannel('A')
    for density, factor in DENSITIES:
        folder = RES / f'mipmap-{density}'
        folder.mkdir(parents=True, exist_ok=True)
        legacy = round(48 * factor)
        adaptive = round(108 * factor)
        layer = centered_mask(master, adaptive, radius=31.8 * factor)
        tint(layer, SIGNAL).save(folder / 'ic_launcher_foreground.png', optimize=True)
        tint(layer, 'white').save(folder / 'ic_launcher_monochrome.png', optimize=True)
        Image.new('RGB', (adaptive, adaptive), INK).save(folder / 'ic_launcher_background.png', optimize=True)
        tile(master, legacy, radius=legacy * 0.418).save(folder / 'ic_launcher.png', optimize=True)
        small_round = Image.new('RGBA', (legacy * 4, legacy * 4))
        ImageDraw.Draw(small_round).ellipse((3, 3, legacy * 4 - 4, legacy * 4 - 4), fill=INK)
        small_round.alpha_composite(tint(centered_mask(master, legacy * 4, radius=legacy * 4 * 0.418), SIGNAL))
        small_round.resize((legacy, legacy), Image.Resampling.LANCZOS).save(folder / 'ic_launcher_round.png', optimize=True)
    for version in (26, 33):
        desc = adaptive_descriptor(version >= 33)
        for name in ('ic_launcher', 'ic_launcher_round'):
            write_file(RES / f'mipmap-anydpi-v{version}/{name}.xml', desc)
    write_file(RES / 'values/ic_launcher_background.xml',
               f'<resources>\n  <color name="ic_launcher_background">{INK}</color>\n</resources>\n')
    dest = RES / 'drawable-nodpi/bf_library_icon.png'
    dest.parent.mkdir(parents=True, exist_ok=True)
    tile(master, 176, 78).save(dest, optimize=True)

if __name__ == '__main__':
    main()

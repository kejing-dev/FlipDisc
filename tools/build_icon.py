"""Flip Disc icon (design-package appIcon): felt green #2F7850 with a faint grid, one thick disc caught mid-flip: left half
black #1B1B1D, right half ivory #EDE6D6, split by a 30° slash that shows a dark grey side. No text.

  python tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes app/src/main/res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon) and
mipmap-xxxhdpi/ic_spatial_launcher.png. The adaptive launcher icon is the vector drawable ic_launcher_fg.xml (same
drawing) on @color/ic_launcher_background.
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
SS = 4
FELT = (0x2F, 0x78, 0x50)
GRID = (0x2A, 0x66, 0x45)
BLACK = (0x1B, 0x1B, 0x1D)
IVORY = (0xED, 0xE6, 0xD6)
SIDE = (0x55, 0x58, 0x5C)

# Artwork in a 108-unit box (the adaptive icon grid); layers scale it to 1024.
C = (54.0, 54.0)
R = 26.0
ANGLE = math.radians(30)  # the slash, from vertical
D = (math.sin(ANGLE), -math.cos(ANGLE))
N = (math.cos(ANGLE), math.sin(ANGLE))  # points to the ivory side
BAND = 1.9


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(im, ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def layer_bg():
    def d(_im, dr, k):
        dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=FELT + (255,))
        for g in (30, 54, 78):
            dr.line((14 * k, g * k, 94 * k, g * k), fill=GRID + (255,), width=round(1.6 * k))
            dr.line((g * k, 14 * k, g * k, 94 * k), fill=GRID + (255,), width=round(1.6 * k))
    return big(d)


def half_plane(k, sign):
    """Polygon covering the side of the slash line where sign * dot(p - C, N) > 0."""
    far = 200
    p1 = (C[0] + D[0] * far, C[1] + D[1] * far)
    p2 = (C[0] - D[0] * far, C[1] - D[1] * far)
    q1 = (p1[0] + N[0] * far * sign, p1[1] + N[1] * far * sign)
    q2 = (p2[0] + N[0] * far * sign, p2[1] + N[1] * far * sign)
    return [(x * k, y * k) for x, y in (p1, q1, q2, p2)]


def layer_disc():
    def d(im, _dr, k):
        disc = Image.new("L", im.size, 0)
        ImageDraw.Draw(disc).ellipse(((C[0] - R) * k, (C[1] - R) * k, (C[0] + R) * k, (C[1] + R) * k), fill=255)
        for sign, col in ((-1, BLACK), (1, IVORY)):
            side = Image.new("L", im.size, 0)
            ImageDraw.Draw(side).polygon(half_plane(k, sign), fill=255)
            mask = Image.composite(disc, Image.new("L", im.size, 0), side)
            im.paste(Image.new("RGBA", im.size, col + (255,)), mask=mask)
    return big(d)


def band_points():
    p1 = (C[0] + D[0] * R, C[1] + D[1] * R)
    p2 = (C[0] - D[0] * R, C[1] - D[1] * R)
    return [(p1[0] - N[0] * BAND, p1[1] - N[1] * BAND), (p1[0] + N[0] * BAND, p1[1] + N[1] * BAND),
            (p2[0] + N[0] * BAND, p2[1] + N[1] * BAND), (p2[0] - N[0] * BAND, p2[1] - N[1] * BAND)]


def layer_side():
    def d(_im, dr, k):
        dr.polygon([(x * k, y * k) for x, y in band_points()], fill=SIDE + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def flatten(layers):
    out = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for layer in layers:
        out.alpha_composite(layer)
    return out


def vector_xml():
    p1 = (C[0] + D[0] * R, C[1] + D[1] * R)
    p2 = (C[0] - D[0] * R, C[1] - D[1] * R)
    f = lambda v: f"{v:.2f}"
    black = f"M{f(p1[0])},{f(p1[1])}A{R:.0f},{R:.0f} 0 0,0 {f(p2[0])},{f(p2[1])}Z"
    ivory = f"M{f(p1[0])},{f(p1[1])}A{R:.0f},{R:.0f} 0 0,1 {f(p2[0])},{f(p2[1])}Z"
    band = "M" + "L".join(f"{f(x)},{f(y)}" for x, y in band_points()) + "Z"
    grid = "M14,30H94M14,54H94M14,78H94M30,14V94M54,14V94M78,14V94"
    return ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            "<!-- Flip Disc launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->\n"
            "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"108dp\" android:height=\"108dp\" "
            "android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n"
            f"    <path android:strokeColor=\"#FF2A6645\" android:strokeWidth=\"1.6\" android:pathData=\"{grid}\" />\n"
            f"    <path android:fillColor=\"#FF1B1B1D\" android:pathData=\"{black}\" />\n"
            f"    <path android:fillColor=\"#FFEDE6D6\" android:pathData=\"{ivory}\" />\n"
            f"    <path android:fillColor=\"#FF55585C\" android:pathData=\"{band}\" />\n"
            "</vector>\n")


def main():
    layers = [layer_bg(), layer_disc(), layer_side()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = flatten(layers)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w", encoding="utf-8").write(vector_xml())
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        tiles = layers + [flat]
        sheet = Image.new("RGB", (w * len(tiles), w), (60, 64, 70))
        for i, t in enumerate(tiles):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()

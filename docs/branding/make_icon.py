"""
Render the WorldShare icon (variant E) to PNG at the sizes the project needs.

Drawn directly with PIL rather than rasterising the SVG: there's no cairo or
ImageMagick on this machine, and supersampling by 4x then downsampling with
LANCZOS gives better edges on the diagonals of an isometric cube than most
SVG rasterisers do anyway.

Geometry is copied verbatim from icon-v2.svg's #split and #ring, including the
arrowheads, which were computed from the arcs' actual tangents.
"""
import os
from PIL import Image, ImageDraw

OUT_DIR = os.path.dirname(os.path.abspath(__file__))

SS = 4                 # supersample factor
PLATE_UNITS = 250.0    # the SVG plate is 250x250 user units
CONTENT_SCALE = 0.72   # filled out: 0.60 left too much dead plate at 32px
CONTENT_DY = -10.0

PLATE = (0x1E, 0x24, 0x30)
SEAM = (0x14, 0x16, 0x1A)
WHITE = (0xEA, 0xF2, 0xFF)

GREEN_TOP = (0x6C, 0xC2, 0x4A)
BLUE_TOP = (0x4E, 0xA8, 0xE0)
BROWN_SIDE = (0x7A, 0x52, 0x30)
BLUE_SIDE = (0x2C, 0x6E, 0x9B)
GREEN_LIP = (0x4E, 0x96, 0x36)
BLUE_LIP = (0x2A, 0x83, 0xB8)


def render(size):
    w = size * SS
    k = w / PLATE_UNITS
    cx = cy = w / 2.0

    def P(x, y):
        """block-space point -> canvas pixel, applying the SVG transform"""
        return (cx + (x * CONTENT_SCALE) * k,
                cy + (y * CONTENT_SCALE + CONTENT_DY) * k)

    def poly(pts):
        return [P(x, y) for x, y in pts]

    img = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # --- background plate ---
    d.rounded_rectangle([0, 0, w - 1, w - 1], radius=int(54 * k), fill=PLATE)

    # --- sync ring: two arcs on r=150, drawn under the block ---
    r_px = 150 * CONTENT_SCALE * k
    ox, oy = P(0, 0)                      # ring centre in canvas space
    stroke = max(1, int(round(26 * CONTENT_SCALE * k)))
    # PIL draws an arc's width INWARD from the bounding box, so a bbox of radius R
    # puts the stroke's centreline at R - width/2. The arrowheads are centred on R,
    # so without this the arc meets each head off-centre, toward its outer corner -
    # which reads as the head being stuck on sideways. Inflate the bbox by half the
    # stroke so the inward-drawn band straddles R instead.
    r_out = r_px + stroke / 2.0
    bbox = [ox - r_out, oy - r_out, ox + r_out, oy + r_out]
    # PIL angles: 0 = 3 o'clock, increasing clockwise (y grows downward),
    # which matches the convention the SVG arc endpoints were derived in.
    d.arc(bbox, 180, 305, fill=WHITE, width=stroke)
    d.arc(bbox, 0, 125, fill=WHITE, width=stroke)
    # Heads: base centred on the arc endpoint and spanning the radial direction,
    # 1.9x the stroke width so it reads as a head rather than a bulge, with the
    # tip far enough along the tangent to bury the stroke's round cap.
    d.polygon(poly([(122.1, -97.6), (100.4, -143.4), (71.7, -102.4)]), fill=WHITE)
    d.polygon(poly([(-122.1, 97.6), (-100.4, 143.4), (-71.7, 102.4)]), fill=WHITE)

    # --- split block ---
    d.polygon(poly([(0, -92), (-106, -31), (0, 30)]), fill=GREEN_TOP)
    d.polygon(poly([(0, -92), (106, -31), (0, 30)]), fill=BLUE_TOP)
    d.polygon(poly([(-106, -31), (0, 30), (0, 122), (-106, 61)]), fill=BROWN_SIDE)
    d.polygon(poly([(106, -31), (0, 30), (0, 122), (106, 61)]), fill=BLUE_SIDE)
    d.polygon(poly([(-106, -31), (0, 30), (0, 50), (-106, -11)]), fill=GREEN_LIP)
    d.polygon(poly([(106, -31), (0, 30), (0, 50), (106, -11)]), fill=BLUE_LIP)

    # --- seam, so the split reads as deliberate ---
    seam_layer = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    ImageDraw.Draw(seam_layer).line(
        [P(0, -92), P(0, 122)], fill=SEAM + (140,),
        width=max(1, int(round(7 * CONTENT_SCALE * k))))
    img = Image.alpha_composite(img, seam_layer)

    return img.resize((size, size), Image.LANCZOS)


TARGETS = [
    (512, "worldshare-icon-512.png", "Modrinth project icon"),
    (128, "worldshare-icon-128.png", "neoforge.mods.toml logoFile"),
    (120, "worldshare-icon-120.png", "Google OAuth consent screen"),
    (32,  "worldshare-icon-32.png",  "legibility check"),
]

for size, name, why in TARGETS:
    path = os.path.join(OUT_DIR, name)
    render(size).save(path, "PNG", optimize=True)
    print("%-28s %4dx%-4d %7d bytes   %s"
          % (name, size, size, os.path.getsize(path), why))

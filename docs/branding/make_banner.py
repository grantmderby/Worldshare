"""
Build the WorldShare wordmark image from an in-game screenshot of the island.

The island is a real Minecraft build, photographed in-game, so this is a
compositor rather than an illustrator: it lifts the island off the sky, draws
the heavy outline around its silhouette, and sets the wordmark underneath in
Minecraft's own font.

Why not just draw it? Because Modrinth forbids AI-generated imagery anywhere on
a project page, and a screenshot of a build you made is the cleanest possible
provenance - which a generated picture of a floating island is not.

Two things here are less obvious than they look.

**Lifting the island off the sky.** The sky is a smooth vertical gradient, so a
single background colour will not do it. Every row is compared against its own
sky, sampled from the far left and right edges where nothing but sky can be, and
that per-row difference is what separates island from background. Holes get
filled afterwards, because a dark stone face can sit close enough to a blue-grey
sky to be missed on colour alone.

**The font is Minecraft's, extracted from the client jar.** A bold sans with a
drop shadow reads as "a logo"; this reads as "a Minecraft mod". Glyph widths are
measured per character from the atlas rather than assumed, since the atlas is a
fixed 8x8 grid but the font is not monospaced.

    python make_banner.py

Writes worldshare-banner.png and worldshare-island-512.png beside this script.
"""
import math
import os
import zipfile

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))

# The build, photographed in-game. Kept in the repo rather than referenced out
# of a screenshots folder, so this stays reproducible after the world is gone.
SCREENSHOT = os.path.join(HERE, "island-source.png")

# Any 1.21.x client jar will do; the ascii atlas has not changed in years.
VERSIONS_DIR = os.path.join(
    os.environ.get("APPDATA", ""), "ModrinthApp", "meta", "versions")

TITLE = "WorldShare"
SUBTITLE = "Synchronized Singleplayer for Two"

# How far a pixel must sit from its row's sky colour to count as island.
# Low enough to catch the dark stone underside, high enough to ignore the
# gradient's own noise.
SKY_TOLERANCE = 26

# Measured at screenshot resolution, so it shrinks with the island. Near-black
# at 14px read as a sticker rather than an outline; slate at 9 keeps the shape
# reading against the sky without stamping on it.
OUTLINE_PX = 9
OUTLINE_INK = (34, 38, 50)

BORDER_PX = 10           # frame around the whole canvas
INK = (17, 17, 20)

# The sync ring, in make_icon.py's colours so the two marks agree.
ARROW_GREEN = (0x6C, 0xC2, 0x4A)
ARROW_BLUE = (0x4E, 0xA8, 0xE0)

# Side of one arrow "pixel" in final image pixels. The ring is drawn this much
# smaller and scaled back up with NEAREST.
PIXEL_SIZE = 8

SKY_TOP = (0xBE, 0xC6, 0xDD)
SKY_BOTTOM = (0x9E, 0xB3, 0xCE)


# ---------------------------------------------------------------- island mask

def island_mask(rgb: np.ndarray) -> np.ndarray:
    """Boolean mask of the island, by per-row difference from the sky."""
    h, w, _ = rgb.shape
    edge = max(8, w // 30)

    # Each row's sky, taken from the margins. Median rather than mean so a
    # stray bright pixel in the margin cannot drag the estimate.
    left = rgb[:, :edge, :]
    right = rgb[:, w - edge:, :]
    sky = np.median(np.concatenate([left, right], axis=1), axis=1)  # (h, 3)

    diff = np.abs(rgb.astype(np.int16) - sky[:, None, :].astype(np.int16))
    mask = diff.max(axis=2) > SKY_TOLERANCE

    # Speckle first, then holes: opening removes the gradient's noise, and
    # filling afterwards closes the dark faces that colour alone missed.
    mask = ndimage.binary_opening(mask, np.ones((3, 3)), iterations=2)
    mask = ndimage.binary_fill_holes(mask)

    # Keep only the largest blob. Distant clouds and compression artefacts in
    # the corners survive everything above and would otherwise get outlined too.
    labels, count = ndimage.label(mask)
    if count > 1:
        sizes = ndimage.sum(mask, labels, range(1, count + 1))
        mask = labels == (int(np.argmax(sizes)) + 1)
    return mask


def outlined_island(path: str):
    """The island cut from its sky, with a heavy outline. Returns RGBA."""
    src = Image.open(path).convert("RGB")
    rgb = np.asarray(src)
    mask = island_mask(rgb)
    if not mask.any():
        raise SystemExit("no island found - is the screenshot mostly sky?")

    ring = ndimage.binary_dilation(mask, np.ones((3, 3)), iterations=OUTLINE_PX)

    # A little air around the outline. Cropping to its exact bounds put the
    # lowest pixel flush with the image edge, where downscaling shaved it and
    # the island looked cut off at the bottom.
    pad = OUTLINE_PX * 2
    ys, xs = np.where(ring)
    box = (max(0, xs.min() - pad),
           max(0, ys.min() - pad),
           min(rgb.shape[1], xs.max() + 1 + pad),
           min(rgb.shape[0], ys.max() + 1 + pad))

    out = np.zeros((rgb.shape[0], rgb.shape[1], 4), dtype=np.uint8)
    out[ring] = (*OUTLINE_INK, 255)  # outline underneath
    out[mask, :3] = rgb[mask]        # island on top
    out[mask, 3] = 255
    return Image.fromarray(out).crop(box)


# ------------------------------------------------------------ minecraft font

def load_font_atlas():
    """The 16x16 glyph sheet from a client jar, as RGBA."""
    jars = []
    for root, _dirs, files in os.walk(VERSIONS_DIR):
        for f in files:
            if f.endswith(".jar"):
                jars.append(os.path.join(root, f))
    # Prefer a 1.21 jar, purely so the glyphs match the game being shipped for.
    jars.sort(key=lambda p: (0 if "1.21" in os.path.basename(p) else 1, p))
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as z:
                with z.open("assets/minecraft/textures/font/ascii.png") as fh:
                    return Image.open(fh).convert("RGBA").copy()
        except (KeyError, zipfile.BadZipFile, OSError):
            continue
    raise SystemExit("couldn't find ascii.png in any client jar")


def glyph_boxes(atlas: Image.Image):
    """Per-character (tile, advance), measuring real widths off the sheet."""
    cw, ch = atlas.width // 16, atlas.height // 16
    alpha = np.asarray(atlas)[:, :, 3]
    boxes = {}
    for code in range(32, 127):
        gx, gy = (code % 16) * cw, (code // 16) * ch
        cell = alpha[gy:gy + ch, gx:gx + cw]
        cols = np.where(cell.any(axis=0))[0]
        # Space carries no pixels, so it gets the font's conventional width.
        advance = (cols.max() + 2) if cols.size else (cw // 2)
        boxes[chr(code)] = ((gx, gy, gx + cw, gy + ch), int(advance))
    return boxes


def draw_text(canvas, atlas, boxes, text, x, y, scale, fill, shadow=None):
    """Blit text from the atlas. Returns the width drawn."""
    cw = atlas.width // 16
    ch = atlas.height // 16

    def blit(ox, oy, colour):
        cx = ox
        for c in text:
            if c not in boxes:
                c = "?"
            (box, advance) = boxes[c]
            glyph = atlas.crop(box).resize(
                (cw * scale, ch * scale), Image.NEAREST)
            tint = Image.new("RGBA", glyph.size, colour)
            tint.putalpha(glyph.getchannel("A"))
            canvas.alpha_composite(tint, (cx, oy))
            cx += advance * scale
        return cx - ox

    if shadow:
        blit(x + scale, y + scale, shadow)
    return blit(x, y, fill)


def text_width(boxes, text, scale):
    return sum(boxes.get(c, boxes["?"])[1] for c in text) * scale


# -------------------------------------------------------------------- arrows

def _tri(xx, yy, p0, p1, p2):
    """Vectorised point-in-triangle."""
    def side(ax, ay, bx, by):
        return (xx - bx) * (ay - by) - (ax - bx) * (yy - by)
    d1 = side(*p0, *p1)
    d2 = side(*p1, *p2)
    d3 = side(*p2, *p0)
    neg = (d1 < 0) | (d2 < 0) | (d3 < 0)
    pos = (d1 > 0) | (d2 > 0) | (d3 > 0)
    return ~(neg & pos)


def _arrow_mask(gw, gh, cx, cy, radius, a0_deg, a1_deg, width, head_scale=2.1):
    """Boolean mask of one arc-with-arrowhead on a gw x gh grid.

    Built by testing every cell against the shape rather than by stamping
    overlapping discs along the path. Discs gave a stroke that bulged and
    pinched by a pixel wherever they happened to land, which is exactly the
    lumpiness that stops pixel art looking deliberate. A band between two radii
    is the same width everywhere by construction.
    """
    yy, xx = np.mgrid[0:gh, 0:gw].astype(float)
    yy += 0.5
    xx += 0.5
    dx, dy = xx - cx, yy - cy
    dist = np.hypot(dx, dy)
    ang = np.degrees(np.arctan2(dy, dx)) % 360.0

    a0, a1 = a0_deg % 360.0, a1_deg % 360.0
    within = (ang >= a0) & (ang <= a1) if a0 <= a1 else (ang >= a0) | (ang <= a1)
    band = (np.abs(dist - radius) <= width / 2.0) & within

    # The head, pointing along the tangent at the arc's far end.
    t = math.radians(a1)
    ux, uy = math.cos(t), math.sin(t)          # radial, outward
    vx, vy = -math.sin(t), math.cos(t)         # tangential, direction of travel
    hl = width * head_scale                    # tip beyond the shaft's end
    hw = width * head_scale * 0.78             # half-span across the shaft
    bx, by = cx + radius * ux, cy + radius * uy
    head = _tri(xx, yy,
                (bx + vx * hl, by + vy * hl),
                (bx + ux * hw, by + uy * hw),
                (bx - ux * hw, by - uy * hw))
    return band | head


def sync_ring(size, cx, cy, radius, width, pix=PIXEL_SIZE):
    """Two arrows chasing each other around a circle: the round trip.

    Both sweep the same way. The mod is not two one-way transfers, it is one
    world going out and coming back, and two arrows pointing at each other
    would say the opposite.

    Composed on a coarse grid and scaled up with NEAREST so the curves step the
    way Minecraft's own art does, and outlined by dilating the shape - the same
    treatment the island gets, so the two sit together instead of looking like
    a drawing laid over a photograph.
    """
    # The grid has to carry the canvas's aspect. It used to be square, which
    # was invisible while the banner was square and wrong the moment it was
    # not: a 240x240 grid resized to 1920x1080 is scaled 8x across and 4.5x
    # down, so the ring came out flattened and its centre climbed from y=540
    # to y=304. One scale factor, two grid dimensions.
    gw = max(32, size[0] // pix)
    gh = max(32, size[1] // pix)
    sx = gw / float(size[0])

    green = _arrow_mask(gw, gh, cx * sx, cy * sx, radius * sx, 200, 344, width * sx)
    blue = _arrow_mask(gw, gh, cx * sx, cy * sx, radius * sx, 20, 164, width * sx)
    shape = green | blue
    outline = ndimage.binary_dilation(shape, np.ones((3, 3))) & ~shape

    rgba = np.zeros((gh, gw, 4), dtype=np.uint8)
    rgba[outline] = (*OUTLINE_INK, 255)
    rgba[green] = (*ARROW_GREEN, 255)
    rgba[blue] = (*ARROW_BLUE, 255)
    return Image.fromarray(rgba).resize(size, Image.NEAREST)


# ------------------------------------------------------------------ compose

def sky_backdrop(size):
    """The island's own sky, cleaned up into a smooth vertical gradient."""
    w, h = size
    top = np.array(SKY_TOP, dtype=float)
    bottom = np.array(SKY_BOTTOM, dtype=float)
    ramp = np.linspace(0.0, 1.0, h)[:, None]
    grad = (top[None, :] * (1 - ramp) + bottom[None, :] * ramp)
    return Image.fromarray(
        np.repeat(grad[:, None, :], w, axis=1).astype(np.uint8)).convert("RGBA")


def build():
    island = outlined_island(SCREENSHOT)
    atlas = load_font_atlas()
    boxes = glyph_boxes(atlas)

    # ---- banner: island above the wordmark, 16:9, for the gallery ----
    #
    # The composition is still laid out against a square of side S. Only the
    # canvas is wider, and sky_backdrop is a pure vertical gradient, so the
    # extra width is the same sky continued outwards - nothing is stretched and
    # nothing has to be invented at the edges. Sizing the artwork off W instead
    # would have scaled the ring and the wordmark with the canvas, which is a
    # different picture rather than a wider one.
    H = S = 1080
    W = int(round(H * 16 / 9))
    canvas = sky_backdrop((W, H))

    art_h = int(H * 0.46)
    scale_f = min(art_h / island.height, (S * 0.62) / island.width)
    art = island.resize(
        (max(1, int(island.width * scale_f)), max(1, int(island.height * scale_f))),
        Image.LANCZOS)

    title_scale = 14
    while text_width(boxes, TITLE, title_scale) > S * 0.90 and title_scale > 1:
        title_scale -= 1
    sub_scale = max(1, round(title_scale / 3.2))
    while text_width(boxes, SUBTITLE, sub_scale) > S * 0.90 and sub_scale > 1:
        sub_scale -= 1

    # Centre the block as a whole rather than placing each piece at a fixed
    # fraction of the canvas. Positioning them separately left the margins
    # unequal, and every change to the island's size or the text scale
    # reintroduced it.
    glyph_h = atlas.height // 16
    gap_art_title = int(H * 0.055)
    gap_title_sub = int(H * 0.028)

    # The ring is the tall part, not the island. It is centred on the island but
    # reaches well past it, so measuring the block by the island alone pushed the
    # ring into the frame at the top and across the wordmark at the bottom.
    ring_w = max(8, int(S * 0.034))
    ring_r = max(art.width, art.height) * 0.66
    ring_extent = ring_r + ring_w / 2.0 + OUTLINE_PX
    art_block_h = int(max(art.height, ring_extent * 2))

    block_h = (art_block_h + gap_art_title + glyph_h * title_scale
               + gap_title_sub + glyph_h * sub_scale)
    y = (H - block_h) // 2

    # Behind the island, so the ring passes around and out of sight rather than
    # sitting on top of it - which is what makes it read as circling.
    cx, cy = W / 2.0, y + art_block_h / 2.0
    canvas.alpha_composite(sync_ring((W, H), cx, cy, ring_r, ring_w))
    canvas.alpha_composite(
        art, (int(cx - art.width / 2), int(cy - art.height / 2)))
    y += art_block_h + gap_art_title

    tw = text_width(boxes, TITLE, title_scale)
    draw_text(canvas, atlas, boxes, TITLE, (W - tw) // 2, y,
              title_scale, (255, 255, 255, 255), shadow=(*INK, 255))
    y += glyph_h * title_scale + gap_title_sub

    sw = text_width(boxes, SUBTITLE, sub_scale)
    draw_text(canvas, atlas, boxes, SUBTITLE, (W - sw) // 2, y,
              sub_scale, (26, 34, 48, 255))

    frame = ImageDraw.Draw(canvas)
    for i in range(BORDER_PX):
        frame.rectangle([i, i, W - 1 - i, H - 1 - i], outline=(*INK, 255))

    banner = os.path.join(HERE, "worldshare-banner.png")
    canvas.convert("RGB").save(banner)
    print("wrote", banner, canvas.size)

    # ---- icon: the island alone, since text is unreadable at 512 ----
    S = 512
    icon = sky_backdrop((S, S))
    fit = min((S * 0.86) / island.width, (S * 0.86) / island.height)
    art = island.resize(
        (max(1, int(island.width * fit)), max(1, int(island.height * fit))),
        Image.LANCZOS)
    icon.alpha_composite(art, ((S - art.width) // 2, (S - art.height) // 2))
    d = ImageDraw.Draw(icon)
    for i in range(BORDER_PX // 2):
        d.rectangle([i, i, S - 1 - i, S - 1 - i], outline=(*INK, 255))

    icon_path = os.path.join(HERE, "worldshare-island-512.png")
    icon.convert("RGB").save(icon_path)
    print("wrote", icon_path, icon.size)


if __name__ == "__main__":
    build()

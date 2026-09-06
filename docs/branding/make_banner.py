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

    ys, xs = np.where(ring)
    box = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)

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

    # ---- banner: island above the wordmark, square, for the gallery ----
    W = H = 1024
    canvas = sky_backdrop((W, H))

    art_h = int(H * 0.60)
    scale_f = min(art_h / island.height, (W * 0.80) / island.width)
    art = island.resize(
        (max(1, int(island.width * scale_f)), max(1, int(island.height * scale_f))),
        Image.LANCZOS)

    title_scale = 9
    while text_width(boxes, TITLE, title_scale) > W * 0.86 and title_scale > 1:
        title_scale -= 1
    sub_scale = max(1, round(title_scale / 2.6))
    while text_width(boxes, SUBTITLE, sub_scale) > W * 0.88 and sub_scale > 1:
        sub_scale -= 1

    # Centre the block as a whole rather than placing each piece at a fixed
    # fraction of the canvas. Positioning them separately left the margins
    # unequal, and every change to the island's size or the text scale
    # reintroduced it.
    glyph_h = atlas.height // 16
    gap_art_title = int(H * 0.055)
    gap_title_sub = int(H * 0.028)

    block_h = (art.height + gap_art_title + glyph_h * title_scale
               + gap_title_sub + glyph_h * sub_scale)
    y = (H - block_h) // 2

    canvas.alpha_composite(art, ((W - art.width) // 2, y))
    y += art.height + gap_art_title

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

"""
Compose annotated screenshots into uniform slides, ready for Canva or a video.

The screenshots are every size from 1432x975 to 3361x868, which is fine for a
document and useless for a slideshow - a video cutting between aspect ratios
jumps. Every slide is 1920x1080 with the shot letterboxed inside it, so the
sequence holds still and only the content changes.

**The account badge is the point of this file.** A two-person walkthrough shown
from two accounts is confusing in a way no amount of narration fixes: three
screens in, the viewer has lost track of whose game they are looking at. Each
slide carries the account in the corner, colour-coded green for the host and
blue for the joiner - the same two colours as the mod's own sync arrows, so the
association is already made by the time anyone reads a word.

    python slide.py

Writes numbered PNGs to slides/ beside this script. Import them into Canva as a
sequence, or feed them to ffmpeg:

    ffmpeg -framerate 1/4 -i slides/host-%02d.png -c:v libx264 -pix_fmt yuv420p host.mp4
"""
import os

from PIL import Image, ImageDraw

import annotate
from annotate import ACCENT, INK, font_at

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "slides")

W, H = 1920, 1080
BG = (24, 28, 36)
HEADER_H = 96
MARGIN = 28

# Green host, blue joiner - make_icon.py's arrow colours, so the badge and the
# logo are saying the same thing.
ACCOUNTS = {
    "A": ("Account A  -  the host", (0x6C, 0xC2, 0x4A)),
    "B": ("Account B  -  joining", (0x4E, 0xA8, 0xE0)),
    "drive": ("Google Drive  -  in a browser", (0xE0, 0xA8, 0x4E)),
}


def pill(d, xy, text, font, fill, anchor="r"):
    x, y = xy
    b = d.textbbox((0, 0), text, font=font)
    tw, th = b[2] - b[0], b[3] - b[1]
    pad = 22
    w, h = tw + pad * 2, th + pad
    if anchor == "r":
        x -= w
    d.rounded_rectangle([x, y, x + w, y + h], radius=h // 2,
                        fill=fill, outline=INK, width=4)
    d.text((x + pad - b[0], y + pad // 2 - b[1]), text, font=font, fill=INK)
    return w


def build(step, deck, index):
    """One slide: header, account badge, annotated shot letterboxed below."""
    # No step badge on the shot: the header already numbers the slide, and two
    # numbers on one frame invite the reader to work out whether they differ.
    src_path = os.path.join(annotate.SRC, step["file"])
    with Image.open(src_path) as probe:
        sw0, sh0 = probe.size
    avail_w0, avail_h0 = W - MARGIN * 2, H - HEADER_H - MARGIN * 2
    shrink = min(avail_w0 / sw0, avail_h0 / sh0)
    shot = Image.open(annotate.render(dict(step, n=None), shrink)).convert("RGB")

    canvas = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(canvas)

    f_title = font_at(42)
    f_badge = font_at(30)

    # Header strip
    d.rectangle([0, 0, W, HEADER_H], fill=(15, 18, 24))
    d.line([(0, HEADER_H), (W, HEADER_H)], fill=ACCENT, width=4)

    title = step.get("title", "")
    d.text((MARGIN + 8, HEADER_H // 2 - 24), "%d." % step["n"], font=f_title, fill=ACCENT)
    num_w = d.textbbox((0, 0), "%d." % step["n"], font=f_title)[2]
    d.text((MARGIN + 24 + num_w, HEADER_H // 2 - 24), title, font=f_title,
           fill=(240, 243, 248))

    label, colour = ACCOUNTS.get(step.get("account", "A"), ACCOUNTS["A"])
    pill(d, (W - MARGIN, 24), label, f_badge, colour)

    # The shot, as large as fits under the header
    avail_w, avail_h = W - MARGIN * 2, H - HEADER_H - MARGIN * 2
    k = min(avail_w / shot.width, avail_h / shot.height)
    sw, sh = int(shot.width * k), int(shot.height * k)
    shot = shot.resize((sw, sh), Image.LANCZOS)
    ox, oy = (W - sw) // 2, HEADER_H + (H - HEADER_H - sh) // 2
    d.rectangle([ox - 3, oy - 3, ox + sw + 2, oy + sh + 2], outline=(70, 78, 92), width=3)
    canvas.paste(shot, (ox, oy))

    os.makedirs(OUT, exist_ok=True)
    dst = os.path.join(OUT, "%s-%02d.png" % (deck, index))
    canvas.save(dst)
    return dst


if __name__ == "__main__":
    import spec
    for deck_name, deck in (("host", spec.HOST), ("guest", spec.GUEST),
                            ("states", spec.STATES)):
        for i, st in enumerate(deck, 1):
            print("wrote", os.path.basename(build(st, deck_name, i)))

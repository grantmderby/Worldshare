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
from annotate import GREEN, BLUE, INK, font_at

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "slides")

W, H = 1920, 1080
# The icon's own plate colour, so a slide and the project icon look related.
BG = (0x1E, 0x24, 0x30)
HEADER_H = 96
MARGIN = 28

# Green host, blue joiner - make_icon.py's arrow colours, so the badge and the
# logo are saying the same thing.
ACCOUNTS = {
    "A": ("Account A  -  the host", GREEN, GREEN),
    "B": ("Account B  -  joining", BLUE, BLUE),
    # Not an account, so not one of the two colours - a pale pill instead, which
    # says "this one isn't Minecraft" without inventing a third brand colour.
    # Its annotations stay blue, which reads well on a white browser page.
    "drive": ("Google Drive  -  in a browser", (0xE4, 0xEA, 0xF2), BLUE),
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


def _annotated(step, file_key, marks_key, shrink, accent):
    """Render one screenshot with the marks belonging to it."""
    sub = dict(step, n=None, file=step[file_key], marks=step.get(marks_key, []))
    return Image.open(annotate.render(sub, shrink, accent=accent)).convert("RGB")


def _header(d, step, accent, pill_col, label, f_title, f_badge):
    d.rectangle([0, 0, W, HEADER_H], fill=(0x15, 0x1A, 0x24))
    for x in range(W):
        t = x / float(W - 1)
        d.line([(x, HEADER_H), (x, HEADER_H + 4)],
               fill=tuple(int(GREEN[i] + (BLUE[i] - GREEN[i]) * t) for i in range(3)))
    num = "%d." % step["n"]
    d.text((MARGIN + 8, HEADER_H // 2 - 24), num, font=f_title, fill=accent)
    nw = d.textbbox((0, 0), num, font=f_title)[2]
    d.text((MARGIN + 24 + nw, HEADER_H // 2 - 24), step.get("title", ""),
           font=f_title, fill=(240, 243, 248))
    pill(d, (W - MARGIN, 24), label, f_badge, pill_col)


def build(step, deck, index):
    """One slide. Two screenshots stacked if the step names a `pair`.

    Pairing exists for the moment a joiner first sees the world listed, where the
    row says Download or Join depending on whether e4mc is installed and whether
    the host is hosting. Those are the same moment, not two steps, and showing
    them apart makes a viewer think they missed something between.
    """
    label, pill_col, accent = ACCOUNTS.get(step.get("account", "A"), ACCOUNTS["A"])
    canvas = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(canvas)
    f_title, f_badge = font_at(42), font_at(30)
    f_cap = font_at(30)
    _header(d, step, accent, pill_col, label, f_title, f_badge)

    body_top = HEADER_H + MARGIN
    body_h = H - body_top - MARGIN

    def probe(name):
        with Image.open(os.path.join(annotate.SRC, name)) as im:
            return im.size

    if step.get("pair"):
        cap_h = 46
        each_h = (body_h - cap_h * 2 - MARGIN) // 2
        panels = [
            (step["file"], "marks", step.get("caption_a", ""), GREEN),
            (step["pair"], "pair_marks", step.get("caption_b", ""), BLUE),
        ]
        y = body_top
        for key, mk, cap, tint in panels:
            sw0, sh0 = probe(key if key == step["file"] else key)
            shrink = min((W - MARGIN * 2) / sw0, each_h / sh0)
            shot = _annotated(dict(step, file=key), "file", mk, shrink, accent) \
                if key == step["file"] else \
                _annotated(dict(step, file=key), "file", mk, shrink, accent)
            sw, sh = int(sw0 * shrink), int(sh0 * shrink)
            shot = shot.resize((sw, sh), Image.LANCZOS)
            ox = (W - sw) // 2
            d.rectangle([ox - 3, y - 3, ox + sw + 2, y + sh + 2],
                        outline=(0x3A, 0x44, 0x56), width=3)
            canvas.paste(shot, (ox, y))
            y += sh + 8
            if cap:
                b = d.textbbox((0, 0), cap, font=f_cap)
                d.text(((W - (b[2] - b[0])) // 2 - b[0], y), cap, font=f_cap, fill=tint)
            y += cap_h + MARGIN // 2
    else:
        sw0, sh0 = probe(step["file"])
        shrink = min((W - MARGIN * 2) / sw0, body_h / sh0)
        shot = _annotated(step, "file", "marks", shrink, accent)
        sw, sh = int(sw0 * shrink), int(sh0 * shrink)
        shot = shot.resize((sw, sh), Image.LANCZOS)
        ox, oy = (W - sw) // 2, body_top + (body_h - sh) // 2
        d.rectangle([ox - 3, oy - 3, ox + sw + 2, oy + sh + 2],
                    outline=(0x3A, 0x44, 0x56), width=3)
        canvas.paste(shot, (ox, oy))

    os.makedirs(OUT, exist_ok=True)
    dst = os.path.join(OUT, "%s-%02d.png" % (deck, index))
    canvas.save(dst)
    return dst


if __name__ == "__main__":
    import spec
    for deck_name, deck in (("host", spec.HOST), ("guest", spec.GUEST)):
        for i, st in enumerate(deck, 1):
            print("wrote", os.path.basename(build(st, deck_name, i)))

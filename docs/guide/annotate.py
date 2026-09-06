"""
Annotate the walkthrough screenshots: highlight rings, arrows, callouts, step numbers.

Written because a folder of 31 raw screenshots is evidence, not a guide. A player
following along needs to know *which* of seven identical grey buttons to press,
and a caption underneath does not tell them - it makes them count.

The one idea that makes this cheap: **Minecraft's buttons find themselves.** They
are a near-neutral grey of a narrow brightness range, drawn as wide rectangles, so
a colour mask plus connected components locates every one on screen and sorts them
top-to-bottom, left-to-right. A step then says "ring button 2" rather than
carrying pixel coordinates that break the moment a screenshot is retaken at a
different size.

Targets that are not buttons - a chat line, a progress bar, a field - fall back to
normalised coordinates, which survive rescaling for the same reason.

    python annotate.py            # renders every step in SPEC
    python annotate.py 4          # just step 4, for iterating

Reads from Downloads, writes to out/ beside this script.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(os.environ.get("USERPROFILE", ""), "Downloads")
OUT = os.path.join(HERE, "out")

# Saturated amber. Minecraft's palette is greens, browns and greys, and its own
# UI text is white - so white annotations vanish into the game and red fights the
# nether tones. Amber sits outside both.
ACCENT = (255, 176, 0)
INK = (20, 20, 24)
PAPER = (250, 250, 252)


# ------------------------------------------------------------ finding things

def find_buttons(img, min_w_frac=0.09, min_h=18):
    """Every Minecraft button on screen, sorted top-to-bottom then left-to-right."""
    a = np.asarray(img.convert("RGB")).astype(int)
    mx, mn = a.max(axis=2), a.min(axis=2)
    grey = (mx - mn < 22) & (mn > 105) & (mx < 205)
    grey = ndimage.binary_closing(grey, np.ones((3, 3)))
    lab, _ = ndimage.label(grey)
    H, W = grey.shape
    out = []
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        ys, xs = sl
        w, h = xs.stop - xs.start, ys.stop - ys.start
        if w > W * min_w_frac and h > min_h and 2.0 < w / h < 22 \
                and (lab[sl] == i).mean() > 0.55:
            out.append((xs.start, ys.start, w, h))
    out.sort(key=lambda t: (round(t[1] / max(1, min_h)), t[0]))
    return out


def resolve(img, target):
    """A target spec -> pixel box. 'button:N' or (x, y, w, h) normalised."""
    W, H = img.size
    if isinstance(target, str) and target.startswith("button:"):
        idx = int(target.split(":")[1])
        bs = find_buttons(img)
        if idx >= len(bs):
            raise SystemExit(
                "only %d buttons found, wanted #%d - run with --debug to see them"
                % (len(bs), idx))
        return bs[idx]
    x, y, w, h = target
    return (int(x * W), int(y * H), int(w * W), int(h * H))


# ---------------------------------------------------------------- drawing

def ring(d, box, pad=14, width=7):
    """A rounded ring around something, drawn twice so it reads on any background."""
    x, y, w, h = box
    r = [x - pad, y - pad, x + w + pad, y + h + pad]
    d.rounded_rectangle(r, radius=int(min(w, h) * 0.35) + pad,
                        outline=INK, width=width + 6)
    d.rounded_rectangle(r, radius=int(min(w, h) * 0.35) + pad,
                        outline=ACCENT, width=width)


def arrow(d, start, end, width=9):
    """Straight arrow, dark-edged so it survives a busy screenshot."""
    import math
    (x0, y0), (x1, y1) = start, end
    ang = math.atan2(y1 - y0, x1 - x0)
    head = width * 3.4
    bx, by = x1 - head * math.cos(ang), y1 - head * math.sin(ang)
    px, py = -math.sin(ang), math.cos(ang)
    for col, extra in ((INK, 6), (ACCENT, 0)):
        d.line([(x0, y0), (bx, by)], fill=col, width=width + extra)
        d.polygon([
            (x1 + extra * math.cos(ang), y1 + extra * math.sin(ang)),
            (bx + px * (head * 0.55 + extra), by + py * (head * 0.55 + extra)),
            (bx - px * (head * 0.55 + extra), by - py * (head * 0.55 + extra)),
        ], fill=col)


def measure(d, text, font, pad):
    b = d.textbbox((0, 0), text, font=font)
    return (b[2] - b[0] + pad * 2, b[3] - b[1] + pad * 2, b)


def callout(d, xy, text, font, pad=18):
    """Text on a solid card, drawn from its top-left. Screenshots are noisy;
    text alone gets lost in them."""
    x, y = xy
    w, h, b = measure(d, text, font, pad)
    d.rounded_rectangle([x, y, x + w, y + h],
                        radius=pad, fill=PAPER, outline=INK, width=5)
    d.text((x + pad - b[0], y + pad - b[1]), text, font=font, fill=INK)


def place_label(d, box, text, font, canvas, want, scale):
    """Put the card somewhere it actually fits, and return where to aim from.

    The first version honoured the requested side and let the card fall off the
    canvas when there was no room - which is exactly what happened on the title
    screen, where the button is nearly full width. Sides are tried in preference
    order and the first that fits wins; if none do, the card is clamped and the
    arrow still finds it.
    """
    W, H = canvas
    bx, by, bw, bh = box
    gap = int(60 * scale)
    cw, ch, _ = measure(d, text, font, int(18 * scale))
    m = int(24 * scale)

    cands = []
    if want == "left":
        cands = ["left", "right", "below", "above"]
    elif want == "above":
        cands = ["above", "below", "right", "left"]
    elif want == "below":
        cands = ["below", "above", "right", "left"]
    else:
        cands = ["right", "left", "below", "above"]

    for side in cands:
        if side == "right":
            x, y = bx + bw + gap, by + bh // 2 - ch // 2
            tip = (bx + bw + int(14 * scale), by + bh // 2)
        elif side == "left":
            x, y = bx - gap - cw, by + bh // 2 - ch // 2
            tip = (bx - int(14 * scale), by + bh // 2)
        elif side == "below":
            x, y = bx + bw // 2 - cw // 2, by + bh + gap
            tip = (bx + bw // 2, by + bh + int(14 * scale))
        else:
            x, y = bx + bw // 2 - cw // 2, by - gap - ch
            tip = (bx + bw // 2, by - int(14 * scale))
        if m <= x and x + cw <= W - m and m <= y and y + ch <= H - m:
            return (x, y), tip, side

    x = min(max(m, bx + bw // 2 - cw // 2), W - m - cw)
    y = min(max(m, by + bh + gap), H - m - ch)
    return (x, y), (bx + bw // 2, by + bh + int(14 * scale)), "below"


def step_badge(d, n, xy, font, r=52):
    x, y = xy
    d.ellipse([x - r, y - r, x + r, y + r], fill=ACCENT, outline=INK, width=7)
    t = str(n)
    b = d.textbbox((0, 0), t, font=font)
    d.text((x - (b[2] - b[0]) / 2 - b[0], y - (b[3] - b[1]) / 2 - b[1]),
           t, font=font, fill=INK)


def font_at(size):
    for name in ("seguibl.ttf", "arialbd.ttf", "segoeuib.ttf"):
        p = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


# ------------------------------------------------------------------- render

def render(step):
    src = os.path.join(SRC, step["file"])
    img = Image.open(src).convert("RGB")
    W, H = img.size
    scale = W / 1900.0                      # specs are written against ~1900px wide
    d = ImageDraw.Draw(img)

    f_label = font_at(max(18, int(34 * scale)))
    f_badge = font_at(max(20, int(46 * scale)))

    for a in step.get("marks", []):
        box = resolve(img, a["at"])
        if a.get("ring", True):
            ring(d, box, pad=int(14 * scale), width=max(3, int(7 * scale)))
        if "label" in a:
            (cx0, cy0), tip, side = place_label(
                d, box, a["label"], f_label, (W, H), a.get("side", "right"), scale)
            cw, ch, _ = measure(d, a["label"], f_label, int(18 * scale))
            # Aim from the card's nearest edge, so the arrow always starts on
            # the card rather than floating beside it.
            anchors = {
                "right": (cx0, cy0 + ch // 2),
                "left": (cx0 + cw, cy0 + ch // 2),
                "below": (cx0 + cw // 2, cy0),
                "above": (cx0 + cw // 2, cy0 + ch),
            }
            arrow(d, anchors[side], tip, width=max(4, int(9 * scale)))
            callout(d, (cx0, cy0), a["label"], f_label, pad=int(18 * scale))

    if "n" in step:
        step_badge(d, step["n"], (int(70 * scale), int(70 * scale)), f_badge,
                   r=int(52 * scale))

    os.makedirs(OUT, exist_ok=True)
    dst = os.path.join(OUT, "%02d-%s" % (step.get("n", 0), step["file"]))
    img.save(dst)
    return dst


SPEC = []   # filled in by spec.py


if __name__ == "__main__":
    from spec import SPEC as S
    only = int(sys.argv[1]) if len(sys.argv) > 1 else None
    for st in S:
        if only and st.get("n") != only:
            continue
        print("wrote", os.path.basename(render(st)))

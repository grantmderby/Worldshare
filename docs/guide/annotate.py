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

# WorldShare's own two colours, straight from make_icon.py. The annotation
# accent is not fixed: it follows whichever account the slide was taken on, so a
# ring on the host's screen is green and one on the joiner's is blue. Colour then
# carries the same information as the badge in the header, which means a viewer
# who glances at the middle of the frame still knows whose game it is.
#
# Both stay legible on Minecraft's greens and browns because every mark is drawn
# twice - a heavy dark pass underneath, the colour on top - so the shape reads
# even where the hue does not contrast.
GREEN = (0x6C, 0xC2, 0x4A)
BLUE = (0x4E, 0xA8, 0xE0)
ACCENT = BLUE
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


def find_row_action(img):
    """The green action button on a Contributor Worlds row (Download/Open/Join).

    These are not Minecraft's grey widgets - they are green text on a translucent
    green panel - so find_buttons cannot see them. They are always the greenest
    thing in the right-hand third of the row, which is enough to locate them
    without knowing which word they contain or what size the shot happens to be.
    """
    a = np.asarray(img.convert("RGB")).astype(int)
    H, W, _ = a.shape
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    greenish = (g > r + 12) & (g > b + 12) & (g > 60)
    greenish[:, :int(W * 0.60)] = False        # right-hand side only
    greenish = ndimage.binary_closing(greenish, np.ones((5, 25)))
    lab, n = ndimage.label(greenish)
    if n == 0:
        raise SystemExit("no row action button found")
    sizes = ndimage.sum(greenish, lab, range(1, n + 1))
    sl = ndimage.find_objects(lab)[int(np.argmax(sizes))]
    ys, xs = sl
    return (xs.start, ys.start, xs.stop - xs.start, ys.stop - ys.start)


def find_row_band(img, pad_frac=0.55):
    """Vertical extent of a Contributor Worlds row, for cropping to it.

    The row screenshots were taken at whatever height the window happened to be,
    so one carries 300px of empty world under the row and another almost none.
    Stacked on a slide they look like a mistake. Every row has the same landmark:
    a small red remove button at its left edge, which is the only strongly red
    thing on screen - so find that, and take the band around it.
    """
    a = np.asarray(img.convert("RGB")).astype(int)
    H, W, _ = a.shape
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    reddish = (r > g + 30) & (r > b + 30) & (r > 70)
    reddish[:, int(W * 0.25):] = False          # left edge only
    reddish = ndimage.binary_closing(reddish, np.ones((3, 3)))
    lab, n = ndimage.label(reddish)
    if n == 0:
        return None
    sizes = ndimage.sum(reddish, lab, range(1, n + 1))
    ys, xs = ndimage.find_objects(lab)[int(np.argmax(sizes))]
    h = ys.stop - ys.start
    pad = int(h * pad_frac)
    return (max(0, ys.start - pad), min(H, ys.stop + pad))


def crop_to_chat(img, pad=0.04):
    """Crop to Minecraft's chat / command panel, if one can be found confidently.

    The panel is a flat, dark, low-saturation overlay, which is distinctive
    enough to find - but not always: a progress bar sits on the world with no
    panel behind it, and a shot that is already mostly chat has nothing to
    separate. So the result is sanity-checked, and anything implausible is
    treated as "no crop" rather than trusted. Slides that need a crop the
    detector cannot find get an explicit rectangle in spec.py.
    """
    a = np.asarray(img.convert("RGB")).astype(int)
    H, W, _ = a.shape
    v = a.mean(axis=2)
    sat = a.max(axis=2) - a.min(axis=2)
    dark = (v < 90) & (sat < 40)
    dark = ndimage.binary_closing(dark, np.ones((3, 41)))
    lab, n = ndimage.label(dark)
    best = None
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        ys, xs = sl
        w, h = xs.stop - xs.start, ys.stop - ys.start
        if w < W * 0.30 or h < 12 or (lab[sl] == i).mean() < 0.55:
            continue
        # Chat hugs the left edge; the hotbar is centred. Without this the
        # hotbar wins on several shots, being just as dark and just as wide,
        # and the slide ends up showing an inventory bar instead of a command.
        if xs.start > W * 0.15:
            continue
        if w * h > W * H * 0.80:        # it found the whole screenshot
            continue
        if best is None or w * h > best[0]:
            best = (w * h, xs.start, ys.start, w, h)
    if best is None:
        return img
    _, x, y, w, h = best
    px, py = int(W * pad), int(H * pad)
    return img.crop((max(0, x - px), max(0, y - py),
                     min(W, x + w + px), min(H, y + h + py)))


def crop_to_row(img):
    band = find_row_band(img)
    if band is None:
        return img
    y0, y1 = band
    return img.crop((0, y0, img.width, y1))


def resolve(img, target):
    """A target spec -> pixel box. 'button:N' or (x, y, w, h) normalised."""
    W, H = img.size
    if target == "row-action":
        return find_row_action(img)
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

def ring(d, box, pad=14, width=7, accent=None):
    """A rounded ring around something, drawn twice so it reads on any background."""
    x, y, w, h = box
    r = [x - pad, y - pad, x + w + pad, y + h + pad]
    d.rounded_rectangle(r, radius=int(min(w, h) * 0.35) + pad,
                        outline=INK, width=width + 6)
    d.rounded_rectangle(r, radius=int(min(w, h) * 0.35) + pad,
                        outline=accent or ACCENT, width=width)


def arrow(d, start, end, width=9, accent=None):
    """Straight arrow, dark-edged so it survives a busy screenshot."""
    import math
    (x0, y0), (x1, y1) = start, end
    ang = math.atan2(y1 - y0, x1 - x0)
    head = width * 3.4
    bx, by = x1 - head * math.cos(ang), y1 - head * math.sin(ang)
    px, py = -math.sin(ang), math.cos(ang)
    for col, extra in ((INK, 6), (accent or ACCENT, 0)):
        d.line([(x0, y0), (bx, by)], fill=col, width=width + extra)
        d.polygon([
            (x1 + extra * math.cos(ang), y1 + extra * math.sin(ang)),
            (bx + px * (head * 0.55 + extra), by + py * (head * 0.55 + extra)),
            (bx - px * (head * 0.55 + extra), by - py * (head * 0.55 + extra)),
        ], fill=col)


def measure(d, text, font, pad):
    # Pillow routes a string containing a newline through its multiline path on
    # its own, so a two-line label needs nothing here but the matching align.
    b = d.textbbox((0, 0), text, font=font, align="center")
    return (b[2] - b[0] + pad * 2, b[3] - b[1] + pad * 2, b)


def callout(d, xy, text, font, pad=18):
    """Text on a solid card, drawn from its top-left. Screenshots are noisy;
    text alone gets lost in them."""
    x, y = xy
    w, h, b = measure(d, text, font, pad)
    d.rounded_rectangle([x, y, x + w, y + h],
                        radius=pad, fill=PAPER, outline=INK, width=5)
    d.text((x + pad - b[0], y + pad - b[1]), text, font=font, fill=INK,
           align="center")


def place_label(d, box, text, font, canvas, want, scale, obstacles=(), gap_spec=60):
    """Put the card somewhere it actually fits, and return where to aim from.

    The first version honoured the requested side and let the card fall off the
    canvas when there was no room - which is exactly what happened on the title
    screen, where the button is nearly full width. Sides are tried in preference
    order and the first that fits wins; if none do, the card is clamped and the
    arrow still finds it.
    """
    W, H = canvas
    bx, by, bw, bh = box
    # A step can widen this. The default clears most targets, but a ring drawn
    # around a short control eats into it, and on the picker's Insert button
    # that left the card sitting on the thing it was pointing at.
    gap = int(gap_spec * scale)
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
        # Slide along the edge we are not pointing from, rather than giving up.
        # Centring a wide card over a button near the right edge overflowed, so
        # every side failed and the label fell through to the clamp below -
        # which put it on top of the button it was labelling.
        if side in ("above", "below"):
            x = min(max(m, x), W - m - cw)
            if not (m <= y and y + ch <= H - m):
                continue
        else:
            y = min(max(m, y), H - m - ch)
            if not (m <= x and x + cw <= W - m):
                continue
        # Don't cover a different button. A card sitting on Refresh while
        # pointing at Add World tells the reader two things at once.
        card = (x, y, x + cw, y + ch)
        clash = False
        for (ox, oy, ow, oh) in obstacles:
            if not (card[2] < ox or card[0] > ox + ow
                    or card[3] < oy or card[1] > oy + oh):
                clash = True
                break
        if not clash:
            return (x, y), tip, side

    x = min(max(m, bx + bw // 2 - cw // 2), W - m - cw)
    y = min(max(m, by + bh + gap), H - m - ch)
    return (x, y), (bx + bw // 2, by + bh + int(14 * scale)), "below"


def step_badge(d, n, xy, font, r=52, accent=None):
    x, y = xy
    d.ellipse([x - r, y - r, x + r, y + r], fill=accent or ACCENT, outline=INK, width=7)
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

def render(step, shrink=1.0, accent=None):
    src = os.path.join(SRC, step["file"])
    img = Image.open(src).convert("RGB")
    c = step.get("crop")
    if c == "row":
        img = crop_to_row(img)
    elif c == "chat":
        img = crop_to_chat(img)
    elif isinstance(c, (tuple, list)):
        W0, H0 = img.size
        x, y, w, h = c
        img = img.crop((int(x * W0), int(y * H0),
                        int((x + w) * W0), int((y + h) * H0)))
    W, H = img.size
    # Annotations are sized in the pixels they will finally occupy, not the
    # pixels of the source. The screenshots run from 1432 to 3361 wide and are
    # all letterboxed into the same slide, so sizing against the source made a
    # wide shot's labels shrink and a narrow one's balloon. `shrink` is how much
    # the slide will scale this image down; dividing by it cancels that out.
    scale = 1.0 / max(0.05, shrink)                      # specs are written against ~1900px wide
    d = ImageDraw.Draw(img)

    # 42 reads well at the sizes most shots letterbox to. A step can ask for
    # more when its screenshot is wide and short, so the card lands next to
    # chat text that is itself huge on the finished slide.
    f_label = font_at(max(28, int(step.get("label_size", 42) * scale)))
    f_badge = font_at(max(24, int(40 * scale)))

    for a in step.get("marks", []):
        box = resolve(img, a["at"])
        if a.get("ring", True):
            ring(d, box, pad=int(9 * scale), width=max(3, int(5 * scale)), accent=accent)
        if "label" in a:
            try:
                others = [b for b in find_buttons(img) if b != box]
            except Exception:
                others = []
            (cx0, cy0), tip, side = place_label(
                d, box, a["label"], f_label, (W, H), a.get("side", "right"),
                scale, obstacles=others, gap_spec=a.get("gap", 60))
            cw, ch, _ = measure(d, a["label"], f_label, int(18 * scale))
            # Aim from the card's nearest edge, so the arrow always starts on
            # the card rather than floating beside it.
            anchors = {
                "right": (cx0, cy0 + ch // 2),
                "left": (cx0 + cw, cy0 + ch // 2),
                "below": (cx0 + cw // 2, cy0),
                "above": (cx0 + cw // 2, cy0 + ch),
            }
            if a.get("arrow", True):
                arrow(d, anchors[side], tip, width=max(3, int(6 * scale)),
                      accent=accent)
            callout(d, (cx0, cy0), a["label"], f_label, pad=int(12 * scale))

    if step.get("n"):
        step_badge(d, step["n"], (int(58 * scale), int(58 * scale)), f_badge,
                   r=int(42 * scale), accent=accent)

    os.makedirs(OUT, exist_ok=True)
    dst = os.path.join(OUT, "%02d-%s" % (step.get("n") or 0, step["file"]))
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

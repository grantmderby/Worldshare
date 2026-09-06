"""
Encode the slide decks into MP4s.

Uses the ffmpeg that ships with imageio-ffmpeg, so there is nothing to install
separately and nothing to find on PATH.

Four seconds a slide by default, with a cross-fade between them. The fade is not
decoration: cutting hard between two Minecraft screenshots that differ by one
chat line reads as a glitch rather than a step, and a short dissolve makes the
change legible.

    python video.py            # all three decks
    python video.py host 6     # one deck, six seconds a slide
"""
import glob
import os
import subprocess
import sys

import imageio_ffmpeg

HERE = os.path.dirname(os.path.abspath(__file__))
SLIDES = os.path.join(HERE, "slides")
OUT = os.path.join(HERE, "video")

FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
FADE = 0.6          # seconds of cross-fade


def deck_files(deck):
    return sorted(glob.glob(os.path.join(SLIDES, "%s-*.png" % deck)))


def build(deck, seconds=4.0):
    files = deck_files(deck)
    if not files:
        print("no slides for", deck)
        return None
    os.makedirs(OUT, exist_ok=True)
    dst = os.path.join(OUT, "worldshare-%s.mp4" % deck)

    # Each slide becomes its own input, held for `seconds`, then xfade'd onto the
    # running result. Simpler than one concat with a filter graph, and it keeps
    # the timing arithmetic in one place instead of spread across an expression.
    args = [FFMPEG, "-y"]
    for f in files:
        args += ["-loop", "1", "-t", str(seconds), "-i", f]

    if len(files) == 1:
        filt = "[0:v]format=yuv420p[v]"
    else:
        parts, prev, offset = [], "[0:v]", seconds - FADE
        for i in range(1, len(files)):
            out = "[x%d]" % i
            parts.append("%s[%d:v]xfade=transition=fade:duration=%s:offset=%s%s"
                         % (prev, i, FADE, round(offset, 3), out))
            prev = out
            offset += seconds - FADE
        parts.append("%sformat=yuv420p[v]" % prev)
        filt = ";".join(parts)

    args += ["-filter_complex", filt, "-map", "[v]",
             "-c:v", "libx264", "-preset", "medium", "-crf", "18",
             "-r", "30", dst]

    print("encoding %s (%d slides, %.0fs each)..." % (deck, len(files), seconds))
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr[-1800:])
        raise SystemExit("ffmpeg failed for " + deck)
    print("  wrote %s  (%.1f MB)" % (dst, os.path.getsize(dst) / 1048576))
    return dst


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else None
    secs = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
    for deck in ([which] if which else ["host", "guest", "states"]):
        build(deck, secs)

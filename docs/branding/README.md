# Branding assets

Two families here, and they are made differently.

`make_banner.py` composites the **island** - a real Minecraft build, screenshotted
in game. It lifts the island off the sky, outlines the silhouette, and sets the
wordmark in Minecraft's own font, pulled from the client jar. That provenance is
the point: Modrinth forbids AI-generated imagery anywhere on a project page, and
a photograph of something you built is as clean as provenance gets.

`make_icon.py` draws the **abstract mark** from geometry, described below.

Generated, not hand-drawn — `make_icon.py` renders them, so the icon is
reproducible and tweakable rather than a binary nobody can edit.

| File | Where it goes |
|---|---|
| `island-source.png` | the in-game screenshot everything below is built from |
| `worldshare-banner.png` (1024px) | Modrinth gallery, README, anywhere with room for the wordmark |
| `worldshare-island-512.png` | Modrinth project icon |
| `../../src/main/resources/icon.png` (128px) | `logoFile` in `neoforge.mods.toml`; the in-game mod list |
| `worldshare-icon-120.png` | Google OAuth consent screen app logo |
| `worldshare-icon-512.png` | Modrinth project icon |

## Regenerating

```
python make_banner.py    # island composite: banner + 512 icon
python make_icon.py      # abstract mark
```

`make_banner.py`'s knobs are all constants at the top: `OUTLINE_PX` and
`OUTLINE_INK` for the island's outline, `PIXEL_SIZE` for how coarse the ring's
pixels are, `ARROW_GREEN`/`ARROW_BLUE`, and `TITLE`/`SUBTITLE`. Ring width and
radius are set in `build()`, as fractions of the canvas and of the island.

Writes all sizes beside the script. `CONTENT_SCALE` controls how much of the
plate the artwork fills; it was raised from 0.60 to 0.72 because the first pass
left too much dead margin at small sizes.

## Two things that will bite you if you edit it

**PIL draws an arc's `width` inward from the bounding box.** A bbox of radius R
puts the stroke's centreline at `R - width/2`, not at R. The arrowheads are
centred on R, so the bbox is inflated by half the stroke to compensate. Remove
that and the arcs meet the arrowheads off-centre, which reads as the heads being
stuck on sideways.

**The arrowheads are computed from the arcs' tangents**, not placed by eye — base
spanning the radial direction at the endpoint, tip along the tangent, base about
1.9x the stroke width. Nudging the coordinates by hand will look subtly wrong at
512px and obviously wrong at 120px.

## Design

An isometric voxel block split green/blue — two players, one world — inside a
two-arc cycle, for a world in circulation between them. No Mojang assets or
recognisable Minecraft designs are used; the geometry is original, which matters
for both Google's review and Modrinth's content rules.

The 32px render is included as a legibility check. The ring survives at that
size, the green/blue split mostly doesn't — acceptable, since no slot we
actually ship to is smaller than 120px.

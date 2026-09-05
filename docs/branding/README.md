# Branding assets

Generated, not hand-drawn — `make_icon.py` renders them, so the icon is
reproducible and tweakable rather than a binary nobody can edit.

| File | Where it goes |
|---|---|
| `../../src/main/resources/icon.png` (128px) | `logoFile` in `neoforge.mods.toml`; the in-game mod list |
| `worldshare-icon-120-consent-screen.png` | Google OAuth consent screen app logo |
| `worldshare-icon-512.png` | Modrinth project icon |

## Regenerating

```
python make_icon.py
```

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

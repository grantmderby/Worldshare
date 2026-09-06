# Task: finish the WorldShare 1.0.0 listing on Modrinth

A handoff brief. Everything needed is here or in the files it names.

## Read this first

**The description is already written and is not yours to change.** The author
wrote it on the Modrinth page themselves. Do not edit, rewrite, "improve",
reformat or replace it, and do not paste anything from `MODRINTH.md` over it.
`MODRINTH.md` is a reference for the *other* fields; where it disagrees with what
is already on the page, the page wins.

Three more rules, because getting them wrong is expensive:

1. **Do not invent claims about the mod.** Everything you enter must come from
   this file, from `MODRINTH.md`, or be verifiable in the repo. If something
   looks missing, say so rather than filling the gap.
2. **The account actions are the author's.** Creating or logging into the
   account, accepting the content rules, and pressing the final submit are
   theirs. Prepare everything up to that point and stop.
3. **Do not generate images.** Modrinth forbids AI-generated imagery anywhere on
   a project page — icon, gallery, description — with no disclosure that makes it
   allowed. Every image is already made and listed below. If you think another is
   needed, ask; do not produce one.

## Files to upload

| What | Path | Notes |
|---|---|---|
| The mod | `build/libs/worldshare-1.0.0.jar` | 6,036,226 bytes. **Not** `worldshare-1.0.0-slim.jar` — that is the unshaded intermediate and will not run. |
| Icon | `docs/branding/worldshare-island-512.png` | The island build, outlined. |
| Gallery | `docs/branding/worldshare-banner.png` | Same island with the sync ring and wordmark. |

Upload **one jar** to the version. Modrinth's guidance is one file per version,
the one most people will download.

Both images are a real in-game screenshot of a build the author made, composited
by `docs/branding/make_banner.py`, with the wordmark set in Minecraft's own font
from the client jar. Nothing about them is generated — that provenance is
deliberate and worth preserving if anyone asks.

## Fields to set

From the metadata table at the top of `MODRINTH.md`. The ones people get wrong:

- **Client side: required. Server side: unsupported.** Not a guess — WorldShare
  works on singleplayer saves and LAN and does nothing on a dedicated server.
  Getting this pair wrong draws "doesn't work" reviews from people who installed
  it server-side.
- **Licence: MIT.** `LICENSE` is in the repo; `THIRD-PARTY-NOTICES.txt` covers
  the bundled Apache-2.0 and MPL-2.0 libraries and ships inside the jar.
- **Dependency: `e4mc`, optional.** Only e4mc. The mod also accepts `e4all`, but
  that is deliberately not advertised — see `docs/FUTURE_WORK.md` for why.
- **Version: 1.0.0**, channel **Release**, game version **1.21.1**, loader
  **NeoForge**.
- **Flag: "Contains AI-generated content".** Must be set — see below.

## The version changelog

There is no release history to summarise; 1.0.0 is the first public version.
Keep it short and factual — what the mod does and that this is the first
release. Do not restate the description.

## The AI disclosure

**Must be set, and must not be softened.** Modrinth requires disclosure when "a
substantial portion of the project's code is a product of AI output", which is
the case here: the code was written by Claude under the author's direction.

If a disclosure field or changelog note is needed, use the **AI disclosure**
section of `MODRINTH.md` as written. If the description already covers it, leave
it alone — see the first rule.

Note the rules also say a project "cannot be entirely or primarily comprised of
content created or derived from generative AI output". That sits in tension with
the disclosure requirement for a project like this one. The author has decided to
disclose fully and submit. Do not try to word around it.

## Links

- **Source** and **Issues** → `https://github.com/grantmderby/Worldshare`
- **Wiki/Website** → the site under `docs/site`, only if it is actually published

## When you are done

Report back with: the project URL, which fields you filled, anything you left
blank and why, and anything that did not fit. **Do not submit for review** —
leave that to the author, along with anything touching the description.

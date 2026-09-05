# Task: publish WorldShare 1.0.0 on Modrinth

A handoff brief. Everything needed to fill in the Modrinth project is here or
linked from here. **The copy itself lives in [`MODRINTH.md`](MODRINTH.md)** —
paste from that file rather than rewriting it.

## Before anything else

Three rules for this task, because getting them wrong is expensive:

1. **Do not invent claims about the mod.** Everything on the page must come from
   `MODRINTH.md` or be verifiable in the repo. If something seems missing, say so
   rather than filling the gap.
2. **The human owns the account actions.** Creating the Modrinth account,
   accepting the content rules, and pressing the final submit are theirs. You can
   prepare everything up to that point.
3. **Read `modrinth.com/legal/rules` before submitting anything.** The project
   discloses AI-generated content and that disclosure is mandatory — see below.

## Files to upload

| What | Path | Notes |
|---|---|---|
| The mod | `build/libs/worldshare-1.0.0.jar` | ~6.0 MB. **Not** `worldshare-1.0.0-slim.jar` — that is the unshaded intermediate and will not run. |
| Icon | `docs/branding/worldshare-icon-512.png` | Drawn by `docs/branding/make_icon.py`. Not AI-generated; the script is in the repo if anyone asks. |

Upload **one jar per version**. Modrinth's guidance is one file per version, the
one most people will download.

## Project settings

Copy from the metadata table at the top of `MODRINTH.md`. The ones people get
wrong:

- **Client side: required. Server side: unsupported.** Not a guess — WorldShare
  works on singleplayer saves and LAN and does nothing on a dedicated server.
  Getting this pair wrong draws "doesn't work" reviews from people who installed
  it server-side.
- **Licence: MIT.** The repo has `LICENSE`, and `THIRD-PARTY-NOTICES.txt` covers
  the bundled Apache-2.0 and MPL-2.0 libraries.
- **Dependency: `e4mc`, optional.** Only e4mc. The mod also accepts
  [e4all](https://modrinth.com/mod/e4all), but that is deliberately not
  advertised — see `FUTURE_WORK.md` for why.
- **Flag: "Contains AI-generated content".** Must be set. See below.

## The AI disclosure

Non-negotiable and already written — use the **AI disclosure** section of
`MODRINTH.md` as-is. The short version: the code was written by Claude under the
author's direction, the author designed and tested it and is responsible for it,
and the repo is linked for anyone who wants to check before installing.

Modrinth's rules require disclosure when *"a substantial portion of the project's
code is a product of AI output"*, and separately say a project *"cannot be
entirely or primarily comprised of content created or derived from generative AI
output"*. Those two sit in tension for this project. The author has decided to
disclose fully and submit. **Do not soften the disclosure to make it read
better** — if moderation asks questions, honesty is the whole defence.

Images are a separate rule: **no AI-generated images anywhere on the page**,
including the icon and gallery. The icon is script-drawn and fine. Any gallery
screenshot must be a real screenshot.

## Gallery

Optional but worth it. Four suggestions are listed at the bottom of
`MODRINTH.md`; they need to be taken in-game, so they are the author's to
capture. Do not generate substitutes.

## Links

- **Source** and **Issues** → `https://github.com/grantmderby/Worldshare`
- **Wiki/Website** → the site under `docs/site`, only if it is actually published

## When you are done

Report back with: the project URL, which fields you filled, anything you left
blank and why, and anything in `MODRINTH.md` that did not fit the form. Do not
submit for review — leave that to the author.

# Google Cloud setup for WorldShare

Everything WorldShare needs from Google, and the exact values to enter. Two
audiences here, in order:

1. **[Publishing checklist](#publishing-checklist)** — the one-time work to make
   the mod installable by the public. Do this first; the verification step has a
   waiting period.
2. **[Development setup](#development-setup)** — for anyone building the mod from
   source with their own credentials.

---

## Why this is needed at all

WorldShare talks to Google Drive on the user's behalf, which means it is an OAuth
application and Google has an opinion about it. The scope it requests determines
how much of an opinion.

WorldShare uses **`drive.file`**, which Google classifies as *non-sensitive*. It
grants access only to files the user personally selects in Google's picker. That
classification is the whole reason publishing is affordable: the broad
`drive.file`-adjacent scope `drive` is *restricted*, and a restricted-scope app
needs a recurring paid CASA security audit — realistically $540–1,800 a year — to
escape a hard cap of 100 test users. `docs/CLOUD_BACKEND_DECISION.md` records how
that conclusion was reached and what was tested.

`drive.file` needs no audit. It needs **brand verification**, which is free.

---

## Publishing checklist

### 1. Prerequisites

- The public site must be live first, because the consent screen requires working
  URLs. It deploys from `docs/site/` via `.github/workflows/pages.yml` on any push
  to `main` that touches those files. Confirm both pages load before continuing:
  - Homepage: `https://grantmderby.github.io/Worldshare/`
  - Privacy policy: `https://grantmderby.github.io/Worldshare/privacy.html`

### 2. Verify domain ownership

Google requires you to own every domain used on the consent screen.

- Go to [Google Search Console](https://search.google.com/search-console) and add
  a property for `grantmderby.github.io`.
- Use the **URL prefix** method — the DNS method is unavailable, since you don't
  control `github.io`'s DNS.
- Choose the **HTML file upload** verification option. Download the
  `google*.html` file it gives you, commit it to `docs/site/`, push, and wait for
  Pages to redeploy before clicking Verify.
- In Google Cloud Console, go to **APIs & Services → OAuth consent screen →
  Branding**, and confirm the verified domain appears under *Authorised domains*.

> **If this fails:** GitHub Pages subdomains are usually verifiable this way, but
> Google has historically been inconsistent about shared hosting domains. If
> Search Console refuses `grantmderby.github.io`, the fallback is a cheap custom
> domain (~$10/yr) pointed at Pages via CNAME, which verifies without argument.
> Decide this before submitting for verification rather than after — a rejected
> submission restarts the wait.

### 3. Configure the OAuth consent screen

**APIs & Services → OAuth consent screen**

| Field | Value |
|---|---|
| User type | External |
| App name | `WorldShare` |
| User support email | your Google account address |
| App logo | the mod icon, 120×120 PNG (see *Outstanding* below) |
| Application home page | `https://grantmderby.github.io/Worldshare/` |
| Application privacy policy | `https://grantmderby.github.io/Worldshare/privacy.html` |
| Application terms of service | leave blank — not required |
| Authorised domains | `grantmderby.github.io` |
| Developer contact | your Google account address |

**Scopes** — add exactly one, and nothing else:

```
https://www.googleapis.com/auth/drive.file
```

Adding any scope Google classifies as sensitive or restricted changes the review
you are subject to, and reintroduces the audit cost this whole design exists to
avoid. If the scope list ever needs to grow, re-read the decision report first.

### 4. Confirm the OAuth client type

**APIs & Services → Credentials**

The client must be of type **Desktop app**. This matters for two reasons:

- Desktop clients allow the loopback redirect (`http://127.0.0.1:<random-port>/`)
  that `LocalRedirectReceiver` listens on, with no pre-registered redirect URI.
- Google treats desktop client secrets as non-confidential, which is why shipping
  `client_secret.json` inside the mod jar is acceptable practice rather than a
  leak. It is not a password; the security boundary is the user's own consent.

### 5. Publish the app

**OAuth consent screen → Publishing status → Publish app**

This is the step that matters most for day-to-day use, and it's worth doing early
even before verification completes:

> While the app sits in **Testing**, refresh tokens expire after **7 days**, so
> every user has to sign in again weekly. This is a property of publishing status,
> not of the scope — it applies to `drive.file` too. Publishing to **Production**
> ends it.

Because the only scope is non-sensitive, publishing does not require the app to
have completed verification first. Brand verification affects how the consent
screen is *presented*, not whether the app functions.

### 6. Submit for brand verification

Once the consent screen is filled in and the domain is verified, submit. Expect
anywhere from a few minutes (automated) to about three business days (manual
review). Until it passes, users may see an "unverified app" interstitial.

### 7. Quota — no action needed, recorded for the record

All installs share one Cloud project's quota. Drive's default per-project ceiling
is far above anything this design generates: a sync costs a handful of API calls
(read one control file, update the touched bucket archives, write the control
file back), not one call per world file as the pre-migration design did. Worth
revisiting only if WorldShare becomes unexpectedly popular.

### Outstanding

- **Mod icon.** Needed in three places: the consent screen logo (120×120 PNG),
  `logoFile` in `neoforge.mods.toml`, and the Modrinth project icon. Not yet
  created — this needs a design decision, not a default.

---

## Development setup

For building from source with your own credentials. You do **not** need to
publish or verify anything to develop or test locally.

1. Create a project at [console.cloud.google.com](https://console.cloud.google.com).
2. **APIs & Services → Library → Google Drive API → Enable.**
3. **APIs & Services → OAuth consent screen:** User type *External*, fill in the
   required name and email fields, and add the single scope
   `https://www.googleapis.com/auth/drive.file`.
4. Add your own Google account (and any test accounts) under **Test users**.
5. **Credentials → Create credentials → OAuth client ID → Desktop app.**
6. Download the JSON and place it at **one** of:
   - `src/main/resources/worldshare/oauth/client_secret.json` — bundled into the
     jar at build time.
   - `<gamedir>/config/worldshare/client_secret.json` — loaded at runtime and
     takes precedence, so you can swap credentials without rebuilding.

Both paths are gitignored. Do not commit either.

### Testing Drive behaviour without launching Minecraft

`tools/oauth-picker-prototype/` holds standalone Python scripts that exercise the
same Drive API paths the mod uses — the consent-plus-picker flow, per-file grant
persistence, and what a folder grant does and doesn't cover. Its README explains
each script. They're how every claim in the decision report was established, and
they're much faster than a game restart when you need to check an API assumption.

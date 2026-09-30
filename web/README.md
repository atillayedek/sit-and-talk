# Web pages

Static pages for the public site (no build step, no tracking, no third-party scripts):

| Page | Purpose |
| --- | --- |
| `index.html` | Landing page |
| `privacy.html`, `terms.html`, `community.html` | Legal texts linked from the app (`PRIVACY_URL`, `TERMS_URL`, `COMMUNITY_URL`) |
| `delete-account.html` | Account deletion instructions (the URL Google Play asks for) |
| `support.html` | Support (`SUPPORT_URL`) |
| `auth/callback.html` | Optional https bridge for e-mail links: forwards `?flow=signup|recovery&code=…` to `sitandtalk://auth-callback/<flow>` |

Deploy the folder to any static host. `assetlinks.json` is intentionally absent: it needs the SHA-256
fingerprint of the real release signing key (see `docs/release.md`). Legal texts must be reviewed by the
operator before publishing — see `docs/known-limitations.md`.

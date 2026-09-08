# fortis.rest — public site

The **Fortis Tech Labs** company site plus the **Fortis Wallet** privacy policy
that Google Play requires. **No build step** — plain HTML, one SVG, four small
JPEGs.

```
site/
  index.html      →  https://fortis.rest/          (Fortis Tech Labs)
  privacy.html    →  https://fortis.rest/privacy   (Pages serves /privacy → /privacy.html)
  icon.svg        →  https://fortis.rest/icon.svg  (kept in sync with web/icon.svg)
  img/*.jpg       →  downscaled app screenshots
```

## Before publishing

Fill the placeholders in `privacy.html`:

- `[DATE]` — the effective date.
- `[registered address]` — Fortis Tech Labs' registered address. Play's crypto
  policy needs a real operator identity.

## Deploy — Cloudflare Pages

`fortis.rest` already lives on Cloudflare (nameservers moved there for the
`api.fortis.rest` tunnel), so Pages is the natural fit — separate origin from
the tunnel, free, global, auto-TLS.

**One-time:**

1. Cloudflare dashboard → **Workers & Pages** → **Create** → **Pages** →
   **Connect to Git** (this repo) — or **Direct upload** if you'd rather not
   connect the repo.
2. Build settings: framework preset **None**, build command **(empty)**, build
   output directory **`site`**.
3. After the first deploy, **Custom domains** → add `fortis.rest` and
   `www.fortis.rest`. Cloudflare creates the DNS records automatically since the
   zone is already there.

**Important:** `api.fortis.rest` stays pointed at the `cloudflared` tunnel —
Pages only takes the apex + `www`. Don't add `api` as a Pages custom domain.

**Updating:** with Git connected, every push to the default branch redeploys.

For direct upload, run the helper (needs Node + a one-time `npx wrangler login`,
or a `CLOUDFLARE_API_TOKEN` env var with the *Cloudflare Pages: Edit* permission):

```
deploy\publish-site.ps1            # or: deploy\publish-site.bat  (double-click)
deploy\publish-site.ps1 -Preview   # throwaway preview build with its own URL
```

It uploads the current `site/` as a production deploy, tagging it with the git
commit. First run creates the Pages project (`fortis-rest`); if you already made
one under a different name, pass `-Project <name>`. `README.md` is skipped via
`site/.assetsignore`.

## Keeping the icon in sync

`site/icon.svg` is a copy of `web/icon.svg` (the app/PWA mark). If the logo
changes, copy it across:

```sh
cp web/icon.svg site/icon.svg
```

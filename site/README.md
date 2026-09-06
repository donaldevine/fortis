# fortis.rest — public site

Static pages for `fortis.rest`: the landing page and the privacy policy that
Google Play requires. **No build step** — plain HTML + one SVG.

```
site/
  index.html      →  https://fortis.rest/
  privacy.html    →  https://fortis.rest/privacy   (Pages serves /privacy → /privacy.html)
  icon.svg        →  https://fortis.rest/icon.svg  (kept in sync with web/icon.svg)
```

## Before publishing

Fill the placeholders in `privacy.html`:

- `[DATE]` — the effective date.
- `[Legal entity name and registered address]` — the operator. Play's crypto
  policy needs a real identity here.
- Section 5 (crash diagnostics) — keep it if the self-hosted crash reporter
  ships, otherwise delete the section.

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
With direct upload, drag the `site/` folder into the Pages project again.

## Keeping the icon in sync

`site/icon.svg` is a copy of `web/icon.svg` (the app/PWA mark). If the logo
changes, copy it across:

```sh
cp web/icon.svg site/icon.svg
```

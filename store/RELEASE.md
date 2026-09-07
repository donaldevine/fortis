# Releasing Fortis Wallet to Google Play

End-to-end: build a signed release, set up the Play Console listing, run the
mandatory closed test, and promote to production.

Everything in [STORE.md](STORE.md) (copy, screenshots, data-safety answers) feeds
into step 4. Assets are in `store/`.

---

## 0. Decide: personal account or organization

Personal (individual) developer accounts created after 13 Nov 2023 must run a
**closed test with ≥ 12 testers opted in for ≥ 14 continuous days** before they
can request production access. **Organization accounts are exempt.**

- **Organization** — needs a free **D-U-N-S number** (Dun & Bradstreet,
  ~1–4 weeks) and business verification. If "Fortis Tech Labs" is a real entity,
  do this; it removes the 12/14 requirement. Apply for the D-U-N-S now — it's the
  slowest step. `https://www.dnb.com/duns-number/get-a-duns.html`
- **Personal** — no D-U-N-S, but you do the closed test (steps 6–8).

Either way: $25 one-time registration, and identity verification (photo ID +
address; for personal accounts, a video selfie may be required). Do this at
`https://play.google.com/console/signup` — verification can take a few days, so
start it in parallel with the build.

---

## 1. Generate the upload keystore (once, ever)

Play App Signing holds the real signing key; you sign uploads with an **upload
key**. Losing it is recoverable (contact Play support); losing the *app signing*
key is not, but Google holds that one.

PowerShell, from `C:\Repos\fortis\android`:

```powershell
keytool -genkeypair -v -keystore ..\fortis-upload.jks -alias fortis `
  -keyalg RSA -keysize 4096 -validity 10000
```

It prompts for a keystore password, a key password (use the same for both —
Gradle expects that), and a name/org (any value; not shown to users).

- The file lands at `C:\Repos\fortis\fortis-upload.jks` (outside the repo).
- **Back it up** — password manager + an offline copy. If you rebuild your
  machine without it you can't push updates without a key reset.

## 2. Wire up signing

Create `C:\Repos\fortis\android\keystore.properties` (git-ignored):

```properties
storeFile=../fortis-upload.jks
storePassword=<the password you just set>
keyAlias=fortis
keyPassword=<same password>
```

The build already reads this: present → release signingConfig; absent → debug
key (which Play rejects). Verify:

```powershell
cd C:\Repos\fortis\android
.\gradlew.bat :app:signingReport   # the `release` variant should show your cert, not "AndroidDebugKey"
```

## 3. Build the release AAB

```powershell
cd C:\Repos\fortis\android
.\gradlew.bat clean :app:bundleRelease
```

Output: `android\app\build\outputs\bundle\release\app-release.aab` (~9 MB).

Notes:
- `versionCode` (currently `1`) must **increase on every upload**. Bump it in
  `android/app/build.gradle.kts` before each new build. `versionName`
  (`"0.1.0"`) is the human string shown on the listing.
- First upload can be `versionCode = 1`.

## 4. Smoke-test the exact release build

Install the AAB's APKs on a real device or the emulator so you test what testers
get (R8-minified, release-signed):

```powershell
# one-time: get bundletool — https://github.com/google/bundletool/releases
java -jar bundletool.jar build-apks `
  --bundle=app\build\outputs\bundle\release\app-release.aab `
  --output=release.apks --mode=universal `
  --ks=..\fortis-upload.jks --ks-key-alias=fortis
java -jar bundletool.jar install-apks --apks=release.apks
```

Run through, on **mainnet, tiny amounts**:
1. Create a wallet → fingerprint/PIN lock → write down the phrase.
2. Receive: fund it with ~2–5k sats from another wallet.
3. Send a small amount out; confirm the "Service fee" row shows; broadcast;
   tap the tx → opens mempool.space / mempool.guide.
4. Force-stop, reopen → unlock → balance still there.
5. Settings → wallet → Recovery phrase → confirm screenshot is blocked.
6. Uninstall, reinstall, **Restore from a recovery phrase** → same balance.

Fix anything that breaks, rebuild, re-test. This is the last cheap chance before
strangers hold it.

---

## 5. Create the app in Play Console

`https://play.google.com/console` → **Create app**.

- App name: `Fortis Wallet`
- Default language: English (United States)
- App or game: **App**
- Free or paid: **Free**
- Declarations: accept the Developer Program Policies + US export laws.

Then work the **Dashboard** → "Set up your app" checklist. The blocking items:

### 5a. App access
All features are available without special access → "All functionality is
available without special access". (No login to demo.)

### 5b. Ads
**No**, the app contains no ads.

### 5c. Content rating
Fill the IARC questionnaire. Category: **Utility / Productivity / Communication**
(or Finance if offered). Answer honestly — no violence, no sexual content, no
gambling, references to alcohol/drugs = none. A wallet typically rates
**Everyone / PEGI 3**. Submit → rating is issued instantly.

### 5d. Target audience and content
- Target age: **18 and over** only. (It's a financial/crypto app — do not
  include under-18; that pulls in Families Policy requirements you don't want.)
- "Appeals to children": **No**.

### 5e. Data safety
From [STORE.md](STORE.md) "Data safety" section and `site/privacy.html`. Key answers:

- **Does your app collect or share user data?** Yes.
- Data types:
  - **Financial info → Other financial info** — wallet addresses and on-chain
    transaction data. Collected + **Shared** (with infrastructure providers).
    Purpose: App functionality. Not required (the app works, you choose to
    transact). Not linked to identity.
  - **App activity / Device IDs / Personal info / Location** — **not collected.**
  - **App info and performance → Crash logs** — Collected, not shared, for app
    functionality / diagnostics. (The self-hosted `/crash` reporter.)
- **Is all data encrypted in transit?** Yes (HTTPS).
- **Can users request data deletion?** Yes — link `support@fortis.rest` and the
  privacy policy.
- Independent security review: No (unless you commission one).

### 5f. Financial features / crypto declaration
Play has a **Financial features** declaration and a **Blockchain-based content**
section. Declare:
- The app provides a **non-custodial crypto wallet** (software wallet). You do
  **not** custody user funds, operate an exchange, or offer trading.
- You are **not** a licensed financial entity, and the app doesn't require one
  for a non-custodial wallet (confirm per your jurisdiction).
- No on-device crypto mining.
- Some countries restrict crypto apps — see step 7 (country selection). You may
  need to exclude a handful (e.g. Egypt, Algeria, Bangladesh, etc.); Play will
  flag disallowed regions.
- If asked for a **registered organization / address**: this is where the
  business entity + address matters (also why `site/privacy.html` should carry a
  real operator address). Personal accounts still declare, using your verified
  personal details.

### 5g. Government apps / News / Health
No to all.

### 5h. Privacy policy
URL field: `https://fortis.rest/privacy` — **this page must be live and reachable
before you submit for review.** If the Cloudflare Pages deploy isn't done, host
`site/privacy.html` anywhere public temporarily (GitHub Pages, a Pages project,
even a gist-backed page) and swap the URL later.

---

## 6. Store listing

**Main store listing:**
- Short description (≤ 80): from [STORE.md](STORE.md).
- Full description (≤ 4000): from [STORE.md](STORE.md).
- App icon: `store/play-icon-512.png` (512×512).
- Feature graphic: `store/feature-graphic.png` (1024×500).
- Phone screenshots: `store/screenshots/*.png` (need 2–8; 16:9 or 9:16, min
  1080px on the short side). Re-grab from the **release** build if you want the
  exact status bar.
- App category: **Finance**. Tags: wallet, bitcoin, crypto.
- Contact: `info@fortis.rest`, `https://fortis.rest`.

---

## 7. Create the closed testing track

Play Console → **Testing → Closed testing → Create track** (name it e.g.
`beta`).

### 7a. Testers
Two ways to manage the list:
- **Google Group (recommended)** — create a group like
  `your-testers@googlegroups.com`, set "Who can join" to **Anyone can ask** or
  **Anyone can join**, and add the group email to the track. Then you just
  approve join requests / people self-join — no editing the console per tester.
- **Email list** — paste up to 100 addresses directly. Fine for a fixed set.

For Discord recruiting, use the Group — testers can join it themselves from the
opt-in link.

### 7b. Countries
Select your target countries. Start with the ones you care about; Play will warn
about crypto-restricted regions — deselect those.

### 7c. Upload the build
**Releases → Create new release** on the closed track:
- Upload `app-release.aab`.
- Play App Signing: **accept** letting Google generate the app signing key
  (first upload only). Your `fortis-upload.jks` is now the enrolled upload key.
- Release name: `0.1.0 (1)`.
- Release notes: `First beta. Non-custodial wallet for BTC and BTCB2 (mainnet). Use small amounts only.`
- Save → **Review release** → **Start rollout to Closed testing**.

First review of a new app can take **a few hours to ~7 days**. You can't invite
testers to install until it's approved and live on the track.

### 7d. Get the links
Once live, the track page shows:
- **Copy link** — a `https://play.google.com/apps/testing/rest.fortis.wallet`
  web opt-in page.
- The Play Store listing link (works only for opted-in testers).

---

## 8. Recruit testers (Discord)

You need **12 distinct Google accounts opted in, kept for 14 consecutive days**.
Google's tracker (Closed testing → track → "Testers" tab) shows progress toward
the requirement.

What counts: a tester who accepted the opt-in and installed from Play. They
don't have to use it daily. Un-opting or uninstalling drops the count and can
reset progress, so tell people to leave it installed.

**Post template:**

> **Beta testers wanted — Fortis Wallet (Android)**
> Non-custodial BTC + Bitcoin Knots BLAKE2b (BTCB2) wallet. Need 12 people to
> stay opted in for 14 days for the Play Store requirement — you don't have to
> use it, just keep it installed.
>
> 1. Join the tester group: <googlegroups link>
> 2. Opt in: https://play.google.com/apps/testing/rest.fortis.wallet
> 3. Install "Fortis Wallet" from the Play Store (the link on that page).
>
> It's mainnet and it's beta — **don't put in more than pocket change**, and
> write down your recovery phrase. Bug reports welcome in this thread. Happy to
> test yours back.

Tester-swap communities if Discord is thin: r/androiddev weekly threads, the
"Android Testers" / "Google Play Beta Testing" Telegram groups.

Keep the thread open for 14 days; nudge anyone who un-opts.

---

## 9. Promote to production

Once the tracker says the 12/14 requirement is met (personal account) — or
immediately (organization account):

1. Play Console → **Testing → Closed testing → your track → Promote release →
   Production**, *or* create a fresh Production release with the same AAB.
2. Complete any remaining Production checklist items (pricing & distribution,
   countries).
3. **Publishing → Send for review.** Production review is typically 1–7 days for
   a first app.
4. When approved, set rollout to 100% (or a staged %).

Updates after that: bump `versionCode`, `bundleRelease`, upload to Production (or
test track → promote), send for review.

---

## Quick reference

| Thing | Value |
|---|---|
| Package | `rest.fortis.wallet` |
| Upload keystore | `C:\Repos\fortis\fortis-upload.jks` (alias `fortis`) — **back up** |
| Signing config | `android/keystore.properties` (git-ignored) |
| Build | `cd android && .\gradlew.bat clean :app:bundleRelease` |
| Output | `android/app/build/outputs/bundle/release/app-release.aab` |
| Bump before each upload | `versionCode` in `android/app/build.gradle.kts` |
| Privacy policy (must be live) | `https://fortis.rest/privacy` |
| Closed test | ≥ 12 testers, ≥ 14 continuous days (personal accounts) |

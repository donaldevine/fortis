# Changelog

Fortis Wallet (Android). Versions are `versionName (versionCode)`.

## 0.1.2 (3) — 2026-09-09

- Backend moved to `api.fortistechlabs.com` (was `api.fortis.rest`). The old
  host keeps working through the transition. If your device can't reach the
  backend it still falls back to public block explorers.
- Website is now [fortistechlabs.com](https://fortistechlabs.com/).

## 0.1.1 (2) — 2026-09-09

First public release. Direct download + [Obtainium](https://obtainium.imranr.dev/).

- Non-custodial wallet for Bitcoin XBT and Bitcoin BTC. Keys and the recovery
  phrase are generated on-device and never leave it.
- Create or restore a wallet (12/24 words + optional BIP-39 passphrase).
- Up to 10 named wallets on either chain.
- One app lock — fingerprint / face / device PIN — opens every wallet. Key held
  in the device's secure hardware (StrongBox where available); Settings warns if
  only software-backed.
- Receive (QR + share), Send (QR scan or paste, fee tiers or custom sat/vB,
  "take the fee from the amount"), History, approximate USD value.
- Opt-in XBT replay protection on BTC sends.
- "Receive" auto-advances past addresses already used.
- Balance count-up animation.
- 75 languages.
- Recovery-phrase and password screens blocked from screenshots / screen
  recording / the app switcher.
- No analytics, no ads, no third-party trackers. The backend only ever sees
  public addresses and finished signed transactions — no account, no sign-up.
- Fortis takes no fee of its own; you set the Bitcoin network fee.

# fortis — native Android wallet

Jetpack Compose. Keys and every signature come from `crates/wallet-ffi` (Rust,
via UniFFI); the encrypted seed lives in DataStore. Same design language as the
web wallet (`/DESIGN.md`), same chain backends (Esplora / a `fortisd` gateway).

```
Compose UI  ─►  WalletViewModel  ─►  wallet-ffi (Rust .so)   keys, coin-select, sign
                                 └►  Backend (OkHttp)         Esplora | fortisd
```

## First-time setup

This scaffold was written without a working Android toolchain to test against, so
**the first Gradle sync will need a few fixes.** Expected:

1. **Install the NDK** — Android Studio → *Settings → Languages & Frameworks →
   Android SDK → SDK Tools* → check **NDK (Side by side)** and **CMake**. Apply.
2. **Point Gradle at `cargo`** — the Gradle daemon usually can't see `~/.cargo/bin`.
   Edit `android/gradle.properties`:
   ```
   fortis.cargo=C:/Users/YOU/.cargo/bin/cargo.exe
   ```
3. **Let Studio bump versions** — `gradle/libs.versions.toml` pins AGP / Kotlin /
   Compose and `gradle/wrapper/gradle-wrapper.properties` pins Gradle. If Studio's
   "AGP upgrade" or a sync error asks for newer, accept it — one-file change.
   Your SDK has API 37 installed; `app/build.gradle.kts` uses `compileSdk = 36`
   (Studio will download it), bump to 37 if you prefer.
4. `rustup target add aarch64-linux-android x86_64-linux-android` (done already if
   you built from this repo).

Then: **Open the `android/` folder in Android Studio** → Sync → Run on an
emulator or device (`arm64-v8a` device, or `x86_64` emulator — the two ABIs the
build compiles).

## How the Rust wiring works

- `org.mozilla.rust-android-gradle` cross-compiles `wallet-ffi` to
  `app/build/rustJniLibs/android/<abi>/libwallet_ffi.so` (task `cargoBuild`).
- A Gradle `Exec` task (`generateUniffiBindings`) then runs
  `cargo run -p wallet-ffi --bin uniffi-bindgen -- generate --library <that .so>
  --language kotlin` into `app/build/generated/uniffi/` (package `uniffi.wallet_ffi`).
- JNA (`net.java.dev.jna:jna@aar`) is the runtime the generated Kotlin uses.

If the Gradle task is fighting you, do it by hand once to unblock the UI work:
```sh
# from repo root, with the NDK on ANDROID_NDK_HOME
cargo install cargo-ndk
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build --release -p wallet-ffi
cargo run -p wallet-ffi --bin uniffi-bindgen -- generate \
  --library android/app/src/main/jniLibs/arm64-v8a/libwallet_ffi.so \
  --language kotlin --out-dir android/app/src/main/kotlin
```
(then drop the `cargo`/`generateUniffiBindings` bits from `app/build.gradle.kts`).

## Layout

| | |
|---|---|
| `MainActivity.kt` / `App.kt` | entry + phase switch (Onboard → Locked → BackendPicker → Home) |
| `WalletViewModel.kt` | orchestration — mirror of `web/src/app.js` |
| `wallet/WalletSession.kt` | wraps the `wallet-ffi` `Wallet` + `WalletView`; seal/unseal |
| `data/Store.kt` | DataStore — the encrypted seed + config |
| `data/EsploraBackend.kt` · `GatewayBackend.kt` | chain backends (OkHttp) |
| `ui/theme/Theme.kt` · `ui/Glass.kt` | the glassy design tokens + components |
| `ui/screens/Screens.kt` | all screens |

## Not done yet

Biometric unlock (dep is included, not wired), StrongBox-backed key wrapping,
balance count-up animation, QR on receive, address gap-limit auto-advance, the
atomic-swap flow. The security-critical parts (derivation, coin selection,
`SIGHASH_UNIFIED` signing) are all in `wallet-ffi` and shared with the web wallet.

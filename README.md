# fortis

A non-custodial wallet for **Bitcoin (SHA256d)** and the **Bitcoin Knots BLAKE2b
hard fork**, with built-in cross-chain atomic swaps.

The security-critical logic is one audited Rust crate. It compiles to WebAssembly
for the browser and to a native library (UniFFI) for iOS / Android — no
reimplementation of the crypto per platform.

## Layout

| Crate | |
|---|---|
| [`crates/wallet-core`](crates/wallet-core) | keys (BIP-32/39/84), addresses, coin selection, HTLC atomic-swap contracts, `SIGHASH_UNIFIED` (Knots PR #357), the client swap state machine, seed sealing. Pure — no I/O. |
| [`crates/wallet-wasm`](crates/wallet-wasm) | `wasm-bindgen` bindings for the web |
| [`crates/wallet-ffi`](crates/wallet-ffi) | UniFFI bindings for mobile |

## Tests

```
cargo test                                  # unit + BIP + SIGHASH_UNIFIED vectors
cargo test --features consensus-verify      # + a full swap round-trip, BTC legs
                                            #   validated by libbitcoinconsensus
```

Building the C parts of `secp256k1-sys` needs a C toolchain (MSVC Build Tools on
Windows); the wasm build additionally needs clang + `rustup target add
wasm32-unknown-unknown`.

## What it is *not*

fortis is the wallet. The exchange that coordinates swaps between fortis users —
offer matching, the watchtower, the order book — is a separate service. fortis
holds keys and signs; it never depends on that service to move funds (the swap
escrow is an on-chain HTLC with a unilateral refund path).

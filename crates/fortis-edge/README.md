# fortis-edge

The public front for the fortis wallet backends. It sits in front of a
[`fortis-index`](../fortis-index) instance (BTCB2) and an Esplora upstream (BTC — a
public explorer, or a [`fortisd --esplora-proxy`](../fortisd)) and adds what a
backend exposed to many wallets needs.

```
wallet ──HTTPS──▶ reverse proxy (TLS) ──▶ fortis-edge ─┬─▶ /btcb2/*  fortis-index
                                                       └─▶ /btc/*  Esplora upstream
```

- **Per-install tokens** — `POST /register` mints an anonymous
  `<id>.<hmac>` token. Verified statelessly with the shared secret (no DB
  lookup), so instances scale out horizontally. `--require-token` enforces it on
  `/{btcb2,btc}/*`.
- **Rate limiting** — token-bucket per token (or per client IP when untokened);
  a stricter bucket on `/register` per IP.
- **Response caching** — short TTLs (tip 5 s, address 5 s, fees 30 s) collapse a
  burst of wallet polls into one upstream hit. `POST /tx` is never cached.
- **CORS** (`--allow-origin`) and **`/metrics`** (Prometheus counters:
  requests, registrations, cache hits, rate-limit / auth rejections, upstream
  errors).

TLS is expected from a reverse proxy (Caddy / nginx) in front — the edge speaks
plain HTTP.

## Run

```sh
cargo run -p fortis-edge -- \
  --bind 0.0.0.0:8098 \
  --btcb2-upstream http://127.0.0.1:8094 \
  --btc-upstream https://mempool.space/api \
  --require-token --trust-forwarded-for \
  --crash-log /var/log/fortis/crashes.ndjson
```

`--crash-log <file>` enables `POST /crash`, which appends one JSON object per
line (`{ts, ip, report}`) — the app's uncaught-exception reporter posts there.
Without the flag `/crash` is 404. Rotate the file yourself (logrotate / a cron).

`--service-fee-address <addr>` turns on the service fee: `GET /pricing`
advertises `{address, bps, floor_sat, cap_sat}` (the client reads it and adds the
percentage output) and `POST /<chain>/tx` is rejected `402` unless the
transaction pays at least `--service-fee-floor-sat` (default 200) to that
address. `--service-fee-bps` (default 25) and `--service-fee-cap-sat` (default
5000, 0 = uncapped) are advertised only. `--network` (default `bitcoin`)
validates the address. Unset → no fee, `/pricing` 404s.

The wallet points its BTCB2 explorer URL at `https://<host>/btcb2` and its BTC one at
`https://<host>/btc`, sending `Authorization: Bearer <token>` (or `?token=<t>`).

## Routes

| | |
|---|---|
| `POST /register` | `{ "token": "<id>.<hmac>" }` |
| `GET \| POST /btcb2/<esplora path>` | → BTCB2 upstream |
| `GET \| POST /btc/<esplora path>` | → BTC upstream |
| `POST /crash` | `204`; appends the body to `--crash-log` (else `404`) |
| `GET /pricing` | `{address, bps, floor_sat, cap_sat}` when a service fee is set (else `404`) |
| `GET /metrics` | Prometheus text |
| `GET /` | health + which chains are served |

## Test

`tests/regtest_e2e.rs` drives a wallet's whole flow through
`fortis-edge → fortis-index → a regtest Knots BLAKE2b node`: register a token,
derive + fund an address, see the confirmed UTXO through the edge, build and
`SIGHASH_UNIFIED`-sign a payment with `wallet-core`, broadcast via `POST
/btcb2/tx`, watch the change output appear unconfirmed (mempool overlay) then
confirm, and check the rate limiter rejects a burst. Opt-in:

```sh
cargo build --workspace
FORTIS_BITCOIND="…/bitcoind.exe" cargo test -p fortis-edge --test regtest_e2e
```

## Not yet

Token revocation (would need a blocklist), distributed rate limiting / cache
(in-memory, per instance), built-in TLS, request-size limits, structured request
logging.

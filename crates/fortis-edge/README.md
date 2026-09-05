# fortis-edge

The public front for the fortis wallet backends. It sits in front of a
[`fortis-index`](../fortis-index) instance (BLK) and an Esplora upstream (BTC — a
public explorer, or a [`fortisd --esplora-proxy`](../fortisd)) and adds what a
backend exposed to many wallets needs.

```
wallet ──HTTPS──▶ reverse proxy (TLS) ──▶ fortis-edge ─┬─▶ /blk/*  fortis-index
                                                       └─▶ /btc/*  Esplora upstream
```

- **Per-install tokens** — `POST /register` mints an anonymous
  `<id>.<hmac>` token. Verified statelessly with the shared secret (no DB
  lookup), so instances scale out horizontally. `--require-token` enforces it on
  `/{blk,btc}/*`.
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
  --blk-upstream http://127.0.0.1:8094 \
  --btc-upstream https://mempool.space/api \
  --require-token --trust-forwarded-for
```

The wallet points its BLK explorer URL at `https://<host>/blk` and its BTC one at
`https://<host>/btc`, sending `Authorization: Bearer <token>` (or `?token=<t>`).

## Routes

| | |
|---|---|
| `POST /register` | `{ "token": "<id>.<hmac>" }` |
| `GET \| POST /blk/<esplora path>` | → BLK upstream |
| `GET \| POST /btc/<esplora path>` | → BTC upstream |
| `GET /metrics` | Prometheus text |
| `GET /` | health + which chains are served |

## Not yet

Token revocation (would need a blocklist), distributed rate limiting / cache
(in-memory, per instance), built-in TLS, request-size limits, structured request
logging.

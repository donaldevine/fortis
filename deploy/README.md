# Deploying the fortis backend

```
wallet ──HTTPS──▶ Caddy / Tailscale ──▶ fortis-edge :8098 ─┬─▶ fortis-index :8094 ──▶ Knots (BTCB2) RPC
   (per-install token)                  (tokens, rate-limit,  │
                                         cache, CORS, metrics) └─▶ Esplora upstream (BTC)
```

The Bitcoin **nodes stay where they are** — the edge and index just need to
reach the Knots node's RPC. Nothing here holds keys.

## 1. Prepare the node

Give the Knots node a static RPC credential so a long-running service survives
node restarts (a `.cookie` rotates on every restart). In `bitcoin.conf`:

```ini
rpcauth=fortis:<salt>$<hash>     # generate with Bitcoin Core's share/rpcauth/rpcauth.py
rpcbind=127.0.0.1               # keep RPC on localhost
rpcallowip=127.0.0.1
```

Restart the node. The `user:password` (the plaintext the script printed, not the
hash) is what `--rpc-auth` takes.

## 2. Run the two services

### Native binaries — Windows or Linux, no Docker

```sh
cargo build --release -p fortis-index -p fortis-edge

# terminal 1 — the BTCB2 index
./target/release/fortis-index \
  --network mainnet --rpc-url http://127.0.0.1:8332 \
  --rpc-auth fortis:YOURPASS \
  --db fortis-index.sqlite --bind 127.0.0.1:8094
# first run indexes from the fork height (961640) — a few minutes

# terminal 2 — the edge
./target/release/fortis-edge \
  --bind 127.0.0.1:8098 \
  --btcb2-upstream http://127.0.0.1:8094 \
  --btc-upstream https://blockstream.info/api \
  --require-token --trust-forwarded-for
```

On Linux, `deploy/systemd/*.service` run these under `systemd` with sandboxing —
copy to `/etc/systemd/system/`, edit the `--rpc-auth` / `--allow-origin`, then
`systemctl enable --now fortis-index fortis-edge`.

### Docker Compose

```sh
cp deploy/.env.example deploy/.env      # fill in BTCB2_RPC_AUTH etc.
docker compose -f deploy/docker-compose.yml up -d --build
```

The containers reach the host's node at `host.docker.internal`. `index` is not
published; `edge` is bound to `127.0.0.1:8098` for a front proxy to pick up.

## 3. Make it reachable

### Tailscale (private, no DNS, no open ports) — recommended to start

```sh
tailscale up
tailscale serve https / http://127.0.0.1:8098      # HTTPS on your tailnet
```

Wallet users on your tailnet point the app's service URL at
`https://<machine>.<tailnet>.ts.net`. Or skip `serve` and use plain
`http://100.x.y.z:8098` from other tailnet devices.

### Public domain + Caddy

Point `api.fortis.example` at the host, then:

```sh
caddy run --config deploy/Caddyfile        # or `--profile tls` in compose
```

Caddy gets a Let's Encrypt cert and reverse-proxies `:443 → 127.0.0.1:8098`,
forwarding `X-Forwarded-For` (which is why the edge runs with
`--trust-forwarded-for` — only enable that behind a proxy you control).

Lock `--allow-origin` to your wallet's origin(s) in production.

## 4. Point the wallet at it

In the app's backend picker, choose **fortis (hosted)** and enter the URL from
step 3 (`https://api.fortis.example`, or the Tailscale one). The app does
`POST /register` for a per-install token and sends it as `Authorization: Bearer`
from then on. Update the default in `web/src/app.js` (`DEFAULT_EDGE`) and
`android/.../Screens.kt` (`DEFAULT_EDGE`) once the URL is fixed.

## Operating

- `GET /metrics` on the edge — Prometheus counters (requests, registrations,
  cache hits, rate-limit / auth rejections, upstream errors).
- `GET /` on the edge and the index — health + which chains are served.
- The index is safe to restart any time (it resumes from its SQLite tip);
  a `--rpc-auth` credential means it reconnects cleanly after a node restart too.

## Not covered yet

Multiple edge replicas behind a load balancer (the rate-limiter and cache are
per-instance), token revocation, `fortis-notify` (push notifications).

//! fortis-edge — the public front for the fortis wallet backends.
//!
//! It sits in front of a `fortis-index` instance (BTCB2) and an Esplora upstream
//! (BTC — a public explorer, or a `fortisd --esplora-proxy`) and adds what a
//! backend exposed to many wallets needs: per-install tokens, per-key rate
//! limiting, short-TTL response caching, locked-down CORS, and `/metrics`.
//! Stateless (HMAC tokens, in-memory limiter/cache) so instances scale out.
//!
//! TLS is expected from a reverse proxy (Caddy / nginx) in front.

mod cache;
mod limit;
mod metrics;
mod pricing;
mod proxy;
mod token;

use std::io::{Read, Write};
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use std::thread;

use anyhow::{anyhow, Result};
use clap::Parser;
use serde_json::json;
use tiny_http::{Header, Method, Request, Response, Server};

use cache::Cache;
use limit::RateLimiter;
use metrics::Metrics;
use proxy::Upstream;

#[derive(Parser)]
#[command(name = "fortis-edge", version, about = "public edge for the fortis wallet backends")]
struct Args {
    /// Address to bind to.
    #[arg(long, default_value = "127.0.0.1:8098")]
    bind: String,
    /// BTCB2 upstream — a `fortis-index` base URL, e.g. http://127.0.0.1:8094.
    #[arg(long)]
    btcb2_upstream: Option<String>,
    /// BTC upstream — an Esplora base URL, e.g. https://mempool.space/api or
    /// http://127.0.0.1:8088/esplora.
    #[arg(long)]
    btc_upstream: Option<String>,
    /// BTCB2 price feed — a mempool-style base URL that serves `/v1/prices`
    /// (e.g. https://mempool.kilombino.com/api). `--btcb2-upstream` (a
    /// `fortis-index`) has no price feed of its own. Unset → `GET /btcb2/v1/prices`
    /// hits the normal upstream (404) and the wallet shows no fiat value.
    /// `/btc/v1/prices` needs nothing here — it rides the BTC Esplora upstream.
    #[arg(long)]
    btcb2_price_upstream: Option<String>,
    /// HMAC secret file for tokens. Default: <home>/fortis-edge.secret.
    #[arg(long)]
    secret_file: Option<PathBuf>,
    /// Reject `/btc/*` and `/btcb2/*` without a valid `Authorization: Bearer`
    /// (or `?token=`) minted by `POST /register`.
    #[arg(long)]
    require_token: bool,
    /// Trust `X-Forwarded-For` for the client IP. Only enable behind a proxy that
    /// sets it and strips any inbound value.
    #[arg(long)]
    trust_forwarded_for: bool,
    /// CORS `Access-Control-Allow-Origin`.
    #[arg(long, default_value = "*")]
    allow_origin: String,
    /// Sustained requests per minute, per token (or per IP if untokened). One
    /// wallet sync fans out to ~1 + 2·(addresses within the gap limit) requests,
    /// and repeats on every refresh, so this is generous per install.
    #[arg(long, default_value_t = 600)]
    rate_per_min: u32,
    /// Rate-limit bucket capacity (burst). Must cover a whole gap-limit scan
    /// (tip + utxo + txs per address) landing at once.
    #[arg(long, default_value_t = 300)]
    rate_burst: u32,
    /// `POST /register` calls allowed per hour, per client IP.
    #[arg(long, default_value_t = 10)]
    register_per_hour: u32,
    /// Max cached responses.
    #[arg(long, default_value_t = 4096)]
    cache_entries: usize,
    /// HTTP worker threads.
    #[arg(long, default_value_t = 4)]
    workers: usize,
    /// Append `POST /crash` reports to this file as NDJSON (one JSON object per
    /// line). Unset → `/crash` returns 404.
    #[arg(long)]
    crash_log: Option<PathBuf>,
    /// Network the fee address is on: `bitcoin` (default), `testnet`, `signet`,
    /// `regtest`. Only used to validate `--service-fee-address`.
    #[arg(long, default_value = "bitcoin")]
    network: bitcoin::Network,
    /// Service-fee address. When set, `GET /pricing` advertises the fee and
    /// `POST /<chain>/tx` is rejected unless the transaction pays at least
    /// `--service-fee-floor-sat` to this address. Unset → no fee, `/pricing` 404s.
    #[arg(long)]
    service_fee_address: Option<String>,
    /// Service fee, basis points of the amount sent — default 100 (1%).
    /// Advertised; the client adds the output, the edge enforces the floor.
    #[arg(long, default_value_t = 100)]
    service_fee_bps: u32,
    /// Minimum service fee per transaction, satoshis. This is what the edge
    /// enforces on broadcast. Keep it at or above the dust limit (~294 for a
    /// bech32 address) — a smaller output makes the whole transaction
    /// non-standard and the node rejects it.
    #[arg(long, default_value_t = 546)]
    service_fee_floor_sat: u64,
    /// Maximum service fee per transaction, satoshis (0 = uncapped). Advertised only.
    #[arg(long, default_value_t = 0)]
    service_fee_cap_sat: u64,
}

struct State {
    secret: Vec<u8>,
    require_token: bool,
    trust_forwarded_for: bool,
    allow_origin: String,
    btcb2: Option<Upstream>,
    btc: Option<Upstream>,
    btcb2_price: Option<Upstream>,
    limiter: RateLimiter,
    register_limiter: RateLimiter,
    crash_limiter: RateLimiter,
    crash_log: Option<PathBuf>,
    pricing: Option<pricing::Pricing>,
    cache: Cache,
    metrics: Metrics,
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e:#}");
            ExitCode::FAILURE
        }
    }
}

fn default_home() -> PathBuf {
    #[cfg(windows)]
    {
        PathBuf::from(std::env::var("APPDATA").unwrap_or_else(|_| ".".into())).join("fortis-edge")
    }
    #[cfg(not(windows))]
    {
        PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".fortis-edge")
    }
}

fn run() -> Result<()> {
    let args = Args::parse();
    if args.btcb2_upstream.is_none() && args.btc_upstream.is_none() {
        return Err(anyhow!("set at least one of --btcb2-upstream / --btc-upstream"));
    }
    let secret_file = args
        .secret_file
        .clone()
        .unwrap_or_else(|| default_home().join("fortis-edge.secret"));
    let secret = token::load_or_create_secret(&secret_file)?;

    let fee = match &args.service_fee_address {
        Some(addr) => Some(pricing::Pricing::new(
            addr.clone(),
            args.network,
            args.service_fee_bps,
            args.service_fee_floor_sat,
            args.service_fee_cap_sat,
        )?),
        None => None,
    };

    let state = Arc::new(State {
        secret,
        require_token: args.require_token,
        trust_forwarded_for: args.trust_forwarded_for,
        allow_origin: args.allow_origin.clone(),
        btcb2: args.btcb2_upstream.as_deref().map(Upstream::new),
        btc: args.btc_upstream.as_deref().map(Upstream::new),
        btcb2_price: args.btcb2_price_upstream.as_deref().map(Upstream::new),
        limiter: RateLimiter::new(args.rate_per_min, args.rate_burst),
        register_limiter: RateLimiter::new(args.register_per_hour, args.register_per_hour.max(1)),
        // crashes are rare per device; this just caps a crash-looping client or abuse
        crash_limiter: RateLimiter::new(2, 8),
        crash_log: args.crash_log.clone(),
        pricing: fee,
        cache: Cache::new(args.cache_entries),
        metrics: Metrics::default(),
    });

    let server = Arc::new(
        Server::http(&args.bind).map_err(|e| anyhow!("cannot bind {}: {e}", args.bind))?,
    );

    eprintln!("fortis-edge listening on  http://{}", args.bind);
    eprintln!("  btcb2 upstream   {}", args.btcb2_upstream.as_deref().unwrap_or("(none)"));
    eprintln!("  btc upstream   {}", args.btc_upstream.as_deref().unwrap_or("(none)"));
    eprintln!("  btcb2 price    {}", args.btcb2_price_upstream.as_deref().unwrap_or("(none)"));
    eprintln!("  token auth     {}", if args.require_token { "required" } else { "optional" });
    eprintln!("  rate limit     {}/min, burst {}", args.rate_per_min, args.rate_burst);
    eprintln!("  crash log      {}", args.crash_log.as_deref().map(|p| p.display().to_string()).unwrap_or_else(|| "(disabled)".into()));
    eprintln!(
        "  service fee    {}",
        match &args.service_fee_address {
            Some(a) => format!(
                "{} bps, floor {} sat → {}",
                args.service_fee_bps, args.service_fee_floor_sat, a
            ),
            None => "(disabled)".into(),
        },
    );

    let mut handles = Vec::new();
    for _ in 0..args.workers.max(1) {
        let server = Arc::clone(&server);
        let state = Arc::clone(&state);
        handles.push(thread::spawn(move || {
            for mut req in server.incoming_requests() {
                let reply = handle(&mut req, &state);
                let _ = respond(req, reply, &state.allow_origin);
            }
        }));
    }
    for h in handles {
        let _ = h.join();
    }
    Ok(())
}

enum Reply {
    Json(u16, serde_json::Value),
    Raw(u16, String, Vec<u8>),
    Empty(u16),
}
fn err(status: u16, msg: &str) -> Reply {
    Reply::Json(status, json!({ "error": msg }))
}

fn client_ip(req: &Request, trust_xff: bool) -> String {
    if trust_xff {
        if let Some(xff) = req
            .headers()
            .iter()
            .find(|h| h.field.equiv("X-Forwarded-For"))
        {
            if let Some(first) = xff.value.as_str().split(',').next() {
                let ip = first.trim();
                if !ip.is_empty() {
                    return ip.to_string();
                }
            }
        }
    }
    req.remote_addr()
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|| "unknown".into())
}

fn bearer(req: &Request) -> Option<String> {
    if let Some(h) = req.headers().iter().find(|h| h.field.equiv("Authorization")) {
        if let Some(t) = h.value.as_str().strip_prefix("Bearer ") {
            return Some(t.to_string());
        }
    }
    // fallback: ?token= in the query
    let url = req.url();
    let (_, query) = url.split_once('?')?;
    query
        .split('&')
        .filter_map(|kv| kv.split_once('='))
        .find(|(k, _)| *k == "token")
        .map(|(_, v)| v.to_string())
}

fn handle(req: &mut Request, st: &State) -> Reply {
    Metrics::inc(&st.metrics.requests);
    let method = req.method().clone();
    if method == Method::Options {
        return Reply::Empty(204);
    }

    let url = req.url().to_string();
    let (path, query) = url.split_once('?').unwrap_or((url.as_str(), ""));
    let path = path.trim_end_matches('/');

    match (&method, path) {
        (Method::Get, "") | (Method::Get, "/") => Reply::Json(
            200,
            json!({
                "name": "fortis-edge",
                "version": env!("CARGO_PKG_VERSION"),
                "chains": {
                    "btcb2": st.btcb2.is_some(),
                    "btc": st.btc.is_some(),
                },
            }),
        ),
        (Method::Get, "/metrics") => {
            Reply::Raw(200, "text/plain; version=0.0.4".into(), st.metrics.render().into_bytes())
        }
        (Method::Get, "/pricing") => match &st.pricing {
            Some(p) => Reply::Json(200, p.as_json()),
            None => err(404, "no service fee"),
        },
        (Method::Post, "/register") => {
            let ip = client_ip(req, st.trust_forwarded_for);
            if !st.register_limiter.check(&ip) {
                Metrics::inc(&st.metrics.rate_limited);
                return err(429, "too many registrations — try later");
            }
            match token::issue(&st.secret) {
                Ok(t) => {
                    Metrics::inc(&st.metrics.registered);
                    Reply::Json(200, json!({ "token": t }))
                }
                Err(e) => err(500, &e.to_string()),
            }
        }
        (Method::Post, "/crash") => crash_report(req, st),
        (_, p) if p.starts_with("/btcb2/") || p.starts_with("/btc/") => {
            proxy_chain(req, st, &method, p, query)
        }
        _ => err(404, "no such route"),
    }
}

/// Append one crash report to the NDJSON log. Body is opaque client JSON, capped
/// at 64 KiB; the line adds a server timestamp and the client IP.
fn crash_report(req: &mut Request, st: &State) -> Reply {
    let Some(path) = st.crash_log.as_ref() else {
        return err(404, "no such route");
    };
    let ip = client_ip(req, st.trust_forwarded_for);
    if !st.crash_limiter.check(&ip) {
        Metrics::inc(&st.metrics.rate_limited);
        return err(429, "slow down");
    }

    let mut body = Vec::new();
    let _ = req.as_reader().take(64 * 1024).read_to_end(&mut body);
    let report: serde_json::Value = serde_json::from_slice(&body)
        .unwrap_or_else(|_| json!({ "raw": String::from_utf8_lossy(&body) }));

    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let line = json!({ "ts": ts, "ip": ip, "report": report });

    let ok = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .and_then(|mut f| writeln!(f, "{line}"))
        .is_ok();
    if !ok {
        return err(500, "could not record");
    }
    Metrics::inc(&st.metrics.crash_reports);
    Reply::Empty(204)
}

fn proxy_chain(req: &mut Request, st: &State, method: &Method, path: &str, query: &str) -> Reply {
    let (chain, rest) = path[1..].split_once('/').unwrap_or((&path[1..], ""));

    // A `fortis-index` has no price feed — route `GET /btcb2/v1/prices` to the
    // dedicated price upstream when configured, else let it fall through (404).
    let price_route = method == &Method::Get && chain == "btcb2" && rest == "v1/prices";
    let upstream = match chain {
        "btcb2" if price_route => st.btcb2_price.as_ref().or(st.btcb2.as_ref()),
        "btcb2" => st.btcb2.as_ref(),
        "btc" => st.btc.as_ref(),
        _ => None,
    };
    let Some(upstream) = upstream else {
        return err(404, "that chain is not served here");
    };

    let tok = bearer(req);
    if st.require_token {
        match &tok {
            Some(t) if token::verify(&st.secret, t) => {}
            _ => {
                Metrics::inc(&st.metrics.unauthorized);
                return err(401, "missing or invalid token — POST /register first");
            }
        }
    }

    // Cache hits never touch the upstream, so they don't spend rate budget —
    // check the cache before the limiter.
    let cache_key = format!("{chain}/{rest}?{query}");
    let ttl = if method == &Method::Get { cache::ttl_for(path) } else { None };
    if ttl.is_some() {
        if let Some(hit) = st.cache.get(&cache_key) {
            Metrics::inc(&st.metrics.cache_hits);
            return Reply::Raw(hit.status, hit.content_type, hit.body);
        }
    }

    let rl_key = tok
        .clone()
        .unwrap_or_else(|| format!("ip:{}", client_ip(req, st.trust_forwarded_for)));
    if !st.limiter.check(&rl_key) {
        Metrics::inc(&st.metrics.rate_limited);
        return err(429, "rate limit exceeded");
    }

    let mut body = Vec::new();
    if method == &Method::Post {
        let _ = req.as_reader().read_to_end(&mut body);
    }

    // Enforce the service fee on broadcast: the transaction must pay at least
    // the floor to the fee address (the client adds the exact percentage output).
    if method == &Method::Post && rest == "tx" {
        if let Some(p) = &st.pricing {
            if let Err(msg) = p.check_tx_hex(&String::from_utf8_lossy(&body)) {
                Metrics::inc(&st.metrics.fee_rejected);
                return err(402, &msg);
            }
        }
    }

    match upstream.forward(method, rest, query, &body) {
        Ok(resp) => {
            if let Some(ttl) = ttl {
                if resp.status == 200 {
                    st.cache.put(
                        &cache_key,
                        ttl,
                        cache::Cached {
                            status: resp.status,
                            content_type: resp.content_type.clone(),
                            body: resp.body.clone(),
                        },
                    );
                }
            }
            Reply::Raw(resp.status, resp.content_type, resp.body)
        }
        Err(e) => {
            Metrics::inc(&st.metrics.upstream_errors);
            err(502, &format!("upstream {chain}: {e}"))
        }
    }
}

fn respond(req: Request, reply: Reply, allow_origin: &str) -> std::io::Result<()> {
    let (status, ctype, data): (u16, String, Vec<u8>) = match reply {
        Reply::Empty(s) => (s, "text/plain".into(), Vec::new()),
        Reply::Json(s, v) => (s, "application/json".into(), serde_json::to_vec(&v).unwrap_or_default()),
        Reply::Raw(s, ct, b) => (s, ct, b),
    };
    let mut resp = Response::from_data(data).with_status_code(status);
    for (k, v) in [
        ("Access-Control-Allow-Origin", allow_origin),
        ("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
        ("Access-Control-Allow-Headers", "authorization, content-type"),
        ("Content-Type", ctype.as_str()),
    ] {
        if let Ok(h) = Header::from_bytes(k.as_bytes(), v.as_bytes()) {
            resp.add_header(h);
        }
    }
    req.respond(resp)
}

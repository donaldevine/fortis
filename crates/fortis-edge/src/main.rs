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
mod proxy;
mod token;

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
    /// Sustained requests per minute, per token (or per IP if untokened).
    #[arg(long, default_value_t = 120)]
    rate_per_min: u32,
    /// Rate-limit bucket capacity (burst).
    #[arg(long, default_value_t = 60)]
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
}

struct State {
    secret: Vec<u8>,
    require_token: bool,
    trust_forwarded_for: bool,
    allow_origin: String,
    btcb2: Option<Upstream>,
    btc: Option<Upstream>,
    limiter: RateLimiter,
    register_limiter: RateLimiter,
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

    let state = Arc::new(State {
        secret,
        require_token: args.require_token,
        trust_forwarded_for: args.trust_forwarded_for,
        allow_origin: args.allow_origin.clone(),
        btcb2: args.btcb2_upstream.as_deref().map(Upstream::new),
        btc: args.btc_upstream.as_deref().map(Upstream::new),
        limiter: RateLimiter::new(args.rate_per_min, args.rate_burst),
        register_limiter: RateLimiter::new(args.register_per_hour, args.register_per_hour.max(1)),
        cache: Cache::new(args.cache_entries),
        metrics: Metrics::default(),
    });

    let server = Arc::new(
        Server::http(&args.bind).map_err(|e| anyhow!("cannot bind {}: {e}", args.bind))?,
    );

    eprintln!("fortis-edge listening on  http://{}", args.bind);
    eprintln!("  btcb2 upstream   {}", args.btcb2_upstream.as_deref().unwrap_or("(none)"));
    eprintln!("  btc upstream   {}", args.btc_upstream.as_deref().unwrap_or("(none)"));
    eprintln!("  token auth     {}", if args.require_token { "required" } else { "optional" });
    eprintln!("  rate limit     {}/min, burst {}", args.rate_per_min, args.rate_burst);

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
        (_, p) if p.starts_with("/btcb2/") || p.starts_with("/btc/") => {
            proxy_chain(req, st, &method, p, query)
        }
        _ => err(404, "no such route"),
    }
}

fn proxy_chain(req: &mut Request, st: &State, method: &Method, path: &str, query: &str) -> Reply {
    let (chain, rest) = path[1..].split_once('/').unwrap_or((&path[1..], ""));
    let upstream = match chain {
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

    let rl_key = tok
        .clone()
        .unwrap_or_else(|| format!("ip:{}", client_ip(req, st.trust_forwarded_for)));
    if !st.limiter.check(&rl_key) {
        Metrics::inc(&st.metrics.rate_limited);
        return err(429, "rate limit exceeded");
    }

    let cache_key = format!("{chain}/{rest}?{query}");
    let ttl = if method == &Method::Get { cache::ttl_for(path) } else { None };
    if ttl.is_some() {
        if let Some(hit) = st.cache.get(&cache_key) {
            Metrics::inc(&st.metrics.cache_hits);
            return Reply::Raw(hit.status, hit.content_type, hit.body);
        }
    }

    let mut body = Vec::new();
    if method == &Method::Post {
        let _ = req.as_reader().read_to_end(&mut body);
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

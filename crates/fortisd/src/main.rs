//! fortisd — a small local HTTP gateway between the fortis web wallet and a
//! Bitcoin Knots / BLAKE2b node. It holds **no keys**: the browser signs; this
//! serves chain data (UTXOs, fees, history, status) and broadcasts finished
//! transactions.
//!
//! Every `/v1/*` route needs `Authorization: Bearer <token>` (printed on startup,
//! stored at `<home>/fortisd.token`). CORS is open by default (`--allow-origin`)
//! since the token is the real guard.

mod handlers;
mod state;

use std::path::PathBuf;
use std::process::ExitCode;

use anyhow::{anyhow, Result};
use clap::Parser;
use serde_json::{json, Value};
use tiny_http::{Header, Method, Request, Response, Server};

use fortis_node::Rpc;
use state::{default_bitcoin_datadir, default_home, load_or_create_token, Settings, State};

#[derive(Parser)]
#[command(name = "fortisd", version, about = "fortis chain-gateway daemon (holds no keys)")]
struct Args {
    /// Node data directory (holds `.cookie`). Default: the platform Bitcoin dir.
    #[arg(long)]
    datadir: Option<PathBuf>,
    /// Node RPC URL. Default: 127.0.0.1:8332 (:18443 for regtest).
    #[arg(long)]
    rpc_url: Option<String>,
    /// Node network: mainnet | regtest.
    #[arg(long, default_value = "mainnet")]
    network: String,
    /// Address to bind the HTTP API to.
    #[arg(long, default_value = "127.0.0.1:8088")]
    bind: String,
    /// State + token directory. Default: %APPDATA%\fortisd or ~/.fortisd.
    #[arg(long)]
    home: Option<PathBuf>,
    /// Explicit RPC cookie file (overrides datadir/.cookie).
    #[arg(long)]
    cookie_file: Option<String>,
    /// CORS `Access-Control-Allow-Origin` value.
    #[arg(long, default_value = "*")]
    allow_origin: String,
    /// Forward `/esplora/*` to this Esplora API (e.g. https://mempool.guide/api),
    /// adding CORS. Lets the web wallet use a public explorer that lacks CORS.
    /// A node is optional in this mode.
    #[arg(long)]
    esplora_proxy: Option<String>,
    /// Print the API token and exit.
    #[arg(long)]
    print_token: bool,
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

fn run() -> Result<()> {
    let args = Args::parse();
    let home = args.home.clone().unwrap_or_else(default_home);
    let (token, fresh) = load_or_create_token(&home)?;
    if args.print_token {
        println!("{token}");
        return Ok(());
    }

    let rpc_url = args.rpc_url.clone().unwrap_or_else(|| {
        if args.network.starts_with("regtest") {
            "http://127.0.0.1:18443".into()
        } else {
            "http://127.0.0.1:8332".into()
        }
    });
    let datadir = args
        .datadir
        .clone()
        .unwrap_or_else(default_bitcoin_datadir)
        .to_string_lossy()
        .into_owned();

    let settings = Settings {
        datadir,
        rpc_url,
        network: args.network.clone(),
        cookie_file: args.cookie_file.clone(),
        allow_origin: args.allow_origin.clone(),
        esplora_proxy: args.esplora_proxy.clone(),
        home: home.clone(),
    };
    let proxy_only = settings.esplora_proxy.is_some();

    let rpc = match &settings.cookie_file {
        Some(cf) => Rpc::new(&settings.rpc_url, std::fs::read_to_string(cf)?.trim()),
        None => match Rpc::new_cookie(&settings.rpc_url, &settings.datadir, &settings.network) {
            Ok(r) => r,
            Err(e) if proxy_only => {
                eprintln!("note: no node ({e}); serving the Esplora proxy only");
                Rpc::new(&settings.rpc_url, "x:x")
            }
            Err(e) => return Err(e),
        },
    };
    // Reach the node unless we're proxy-only.
    let node_line = match fortis_node::chain_status(&rpc) {
        Ok(cs) => format!("{}  ({}, chain {})", settings.rpc_url, cs.subversion, cs.chain),
        Err(e) if proxy_only => format!("(no node — {e})"),
        Err(e) => return Err(e),
    };

    let mut st = State::load(&home);
    let server = Server::http(&args.bind).map_err(|e| anyhow!("cannot bind {}: {e}", args.bind))?;

    eprintln!("fortisd  →  {node_line}");
    if let Some(u) = &settings.esplora_proxy {
        eprintln!("           esplora proxy: /esplora/*  →  {u}");
    }
    if let Some(c) = &st.connected {
        eprintln!("           serving {} / {}  (wallet {})", c.chain, c.network, c.watch_wallet);
    }
    eprintln!("listening on  http://{}", args.bind);
    eprintln!();
    eprintln!("  connect the web app with:");
    if let Some(_u) = &settings.esplora_proxy {
        eprintln!("     explorer URL   http://{}/esplora", args.bind);
    }
    eprintln!("     gateway URL    http://{}", args.bind);
    eprintln!("     token          {token}{}", if fresh { "   (newly generated)" } else { "" });
    eprintln!();

    let http = esplora_agent();
    for mut req in server.incoming_requests() {
        let raw_body = read_body(&mut req);
        let reply = handle(&req, &rpc, &mut st, &settings, &token, &raw_body, &http);
        let _ = respond(req, reply, &settings.allow_origin);
    }
    Ok(())
}

/// What a handler produced. `Text` is a verbatim body (Esplora proxy passthrough).
enum Reply {
    Empty(u16),
    Json(u16, Value),
    Text(u16, String),
}
fn ok(v: Value) -> Reply {
    Reply::Json(200, v)
}
fn err(status: u16, msg: impl std::fmt::Display) -> Reply {
    Reply::Json(status, json!({ "error": msg.to_string() }))
}

fn read_body(req: &mut Request) -> String {
    if req.method() == &Method::Post {
        let mut buf = String::new();
        let _ = req.as_reader().read_to_string(&mut buf);
        buf
    } else {
        String::new()
    }
}

fn handle(
    req: &Request,
    rpc: &Rpc,
    st: &mut State,
    settings: &Settings,
    token: &str,
    raw_body: &str,
    http: &ureq::Agent,
) -> Reply {
    let method = req.method().clone();
    let url = req.url().to_string();
    let (path, query) = url.split_once('?').unwrap_or((url.as_str(), ""));

    if method == Method::Options {
        return Reply::Empty(204);
    }
    if path == "/" && method == Method::Get {
        return ok(json!({ "name": "fortisd", "version": env!("CARGO_PKG_VERSION") }));
    }

    // Esplora passthrough: /esplora/<rest> → <upstream>/<rest>, with CORS. Public
    // data only, so no token required (bind to localhost, or trust your network).
    if let (Some(upstream), Some(rest)) = (&settings.esplora_proxy, path.strip_prefix("/esplora/")) {
        return proxy_esplora(http, &method, upstream, rest, query, raw_body);
    }

    let authorized = req.headers().iter().any(|h| {
        h.field.equiv("Authorization") && h.value.as_str().strip_prefix("Bearer ") == Some(token)
    });
    if !authorized {
        return err(401, "missing or invalid bearer token");
    }

    let body: Value = if raw_body.trim().is_empty() {
        Value::Null
    } else {
        match serde_json::from_str(raw_body) {
            Ok(v) => v,
            Err(e) => return err(400, format!("bad JSON body: {e}")),
        }
    };

    let result: Result<Value> = match (&method, path) {
        (Method::Get, "/v1/status") => handlers::status(rpc, st),
        (Method::Post, "/v1/connect") => serde_json::from_value(body)
            .map_err(Into::into)
            .and_then(|r| handlers::connect(rpc, st, &settings.home, r)),
        (Method::Get, "/v1/balances") => handlers::balances(rpc, st),
        (Method::Get, "/v1/utxos") => handlers::utxos(rpc, st, q_u32(query, "min_conf").unwrap_or(1)),
        (Method::Get, "/v1/feerate") => {
            handlers::feerate(rpc, q_u32(query, "conf_target").unwrap_or(6) as u16)
        }
        (Method::Get, "/v1/history") => handlers::history(rpc, st, q_u32(query, "count").unwrap_or(50)),
        (Method::Post, "/v1/broadcast") => serde_json::from_value(body)
            .map_err(Into::into)
            .and_then(|r| handlers::broadcast(rpc, r)),
        _ => return err(404, "no such route"),
    };

    match result {
        Ok(v) => ok(v),
        Err(e) => err(400, format!("{e:#}")),
    }
}

fn esplora_agent() -> ureq::Agent {
    // ureq's `native-tls` feature needs the connector wired in explicitly.
    let mut b = ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(30));
    if let Ok(tls) = native_tls::TlsConnector::new() {
        b = b.tls_connector(std::sync::Arc::new(tls));
    }
    b.build()
}

fn proxy_esplora(agent: &ureq::Agent, method: &Method, upstream: &str, rest: &str, query: &str, body: &str) -> Reply {
    let base = upstream.trim_end_matches('/');
    let target = if query.is_empty() {
        format!("{base}/{rest}")
    } else {
        format!("{base}/{rest}?{query}")
    };
    let resp = match method {
        Method::Get => agent.get(&target).call(),
        Method::Post => agent.post(&target).set("Content-Type", "text/plain").send_string(body),
        _ => return err(405, "method not allowed"),
    };
    match resp {
        Ok(r) => Reply::Text(r.status(), r.into_string().unwrap_or_default()),
        Err(ureq::Error::Status(code, r)) => Reply::Text(code, r.into_string().unwrap_or_default()),
        Err(e) => err(502, format!("upstream explorer: {e}")),
    }
}

fn respond(req: Request, reply: Reply, allow_origin: &str) -> std::io::Result<()> {
    let (status, ctype, data): (u16, &str, Vec<u8>) = match reply {
        Reply::Empty(s) => (s, "application/json", Vec::new()),
        Reply::Json(s, v) => (s, "application/json", serde_json::to_vec(&v).unwrap_or_default()),
        Reply::Text(s, t) => (s, "text/plain", t.into_bytes()),
    };
    let mut resp = Response::from_data(data).with_status_code(status);
    for (name, value) in [
        ("Access-Control-Allow-Origin", allow_origin),
        ("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
        ("Access-Control-Allow-Headers", "authorization, content-type"),
        ("Content-Type", ctype),
    ] {
        if let Ok(h) = Header::from_bytes(name.as_bytes(), value.as_bytes()) {
            resp.add_header(h);
        }
    }
    req.respond(resp)
}

fn q_u32(query: &str, key: &str) -> Option<u32> {
    query
        .split('&')
        .filter_map(|kv| kv.split_once('='))
        .find(|(k, _)| *k == key)
        .and_then(|(_, v)| v.parse().ok())
}

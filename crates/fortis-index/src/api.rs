//! The Esplora-shaped REST surface the fortis wallet's `EsploraBackend` calls:
//! `/blocks/tip/height`, `/address/:a/utxo`, `/address/:a/txs`,
//! `/v1/fees/recommended`, and `POST /tx`. Public chain data only — no auth; bind
//! to localhost or a trusted network, or front it with a TLS/rate-limiting proxy.

use std::sync::Arc;

use anyhow::{anyhow, Context, Result};
use bitcoin::address::NetworkUnchecked;
use bitcoin::{Address, Network};
use serde_json::{json, Value};
use tiny_http::{Header, Method, Request, Response, Server};

use fortis_node::Rpc;

use crate::store::Store;

enum Reply {
    Json(u16, Value),
    Text(u16, String),
    Empty(u16),
}

pub fn serve(bind: &str, store: Store, rpc: Arc<Rpc>, network: Network) -> Result<()> {
    let server = Server::http(bind).map_err(|e| anyhow!("cannot bind {bind}: {e}"))?;
    eprintln!("fortis-index listening on  http://{bind}");
    for mut req in server.incoming_requests() {
        let body = read_body(&mut req);
        let reply = route(&req, &store, &rpc, network, &body);
        let _ = respond(req, reply);
    }
    Ok(())
}

fn route(req: &Request, store: &Store, rpc: &Rpc, network: Network, body: &str) -> Reply {
    let method = req.method().clone();
    let url = req.url().to_string();
    let path = url.split('?').next().unwrap_or("").trim_end_matches('/');

    if method == Method::Options {
        return Reply::Empty(204);
    }
    match (&method, path) {
        (Method::Get, "") | (Method::Get, "/") => Reply::Json(
            200,
            json!({ "name": "fortis-index", "version": env!("CARGO_PKG_VERSION") }),
        ),
        (Method::Get, "/blocks/tip/height") => match store.tip() {
            Ok(Some((h, _))) => Reply::Text(200, h.to_string()),
            Ok(None) => Reply::Text(200, "0".into()),
            Err(e) => err(500, e),
        },
        (Method::Get, "/v1/fees/recommended") => Reply::Json(200, recommended_fees(rpc)),
        (Method::Post, "/tx") => match fortis_node::broadcast(rpc, body.trim()) {
            Ok(txid) => Reply::Text(200, txid.to_string()),
            Err(e) => Reply::Text(400, format!("{e:#}")),
        },
        (Method::Get, p) if p.starts_with("/address/") => {
            address_route(p, store, rpc, network)
        }
        _ => err(404, "no such route"),
    }
}

fn address_route(path: &str, store: &Store, rpc: &Rpc, network: Network) -> Reply {
    // /address/<addr>/utxo  or  /address/<addr>/txs
    let rest = &path["/address/".len()..];
    let (addr, tail) = match rest.split_once('/') {
        Some(x) => x,
        None => return err(404, "expected /address/<addr>/utxo or /txs"),
    };
    let spk = match address_spk(addr, network) {
        Ok(s) => s,
        Err(e) => return err(400, e),
    };
    let tip = store.tip().ok().flatten().map(|(h, _)| h).unwrap_or(0);

    match tail {
        "utxo" => match store.utxos_for(&spk) {
            Ok(us) => Reply::Json(
                200,
                Value::Array(
                    us.iter()
                        .map(|u| {
                            json!({
                                "txid": u.txid,
                                "vout": u.vout,
                                "value": u.value_sat,
                                "status": { "confirmed": true, "block_height": u.height },
                            })
                        })
                        .collect(),
                ),
            ),
            Err(e) => err(500, e),
        },
        "txs" => match address_txs(&spk, store, rpc, tip) {
            Ok(v) => Reply::Json(200, v),
            Err(e) => err(502, e),
        },
        _ => err(404, "expected /utxo or /txs"),
    }
}

/// Rebuild an address's transaction list in the Esplora shape the client parses
/// (`vin[].prevout.{scriptpubkey_address,value}`, `vout[].{scriptpubkey_address,
/// value}`, `fee`, `status`). The index stores only txid lists; the full detail
/// comes from `getrawtransaction <txid> 2 <blockhash>` (no txindex needed).
fn address_txs(spk: &str, store: &Store, rpc: &Rpc, tip: u64) -> Result<Value> {
    let rows = store.history_for(spk, 100)?;
    let mut txs = Vec::with_capacity(rows.len());
    for h in rows {
        let t = rpc
            .call("getrawtransaction", json!([h.txid, 2, h.block_hash]))
            .with_context(|| format!("getrawtransaction {}", h.txid))?;
        txs.push(esplora_tx(&t, h.height, tip));
    }
    Ok(Value::Array(txs))
}

fn esplora_tx(t: &Value, height: u64, _tip: u64) -> Value {
    let vin: Vec<Value> = t["vin"]
        .as_array()
        .map(|a| {
            a.iter()
                .map(|i| match i.get("prevout") {
                    Some(p) if !p.is_null() => json!({
                        "prevout": {
                            "scriptpubkey_address": p["scriptPubKey"]["address"],
                            "value": btc_to_sat(&p["value"]),
                        }
                    }),
                    _ => json!({ "prevout": Value::Null }),
                })
                .collect()
        })
        .unwrap_or_default();
    let vout: Vec<Value> = t["vout"]
        .as_array()
        .map(|a| {
            a.iter()
                .map(|o| {
                    json!({
                        "scriptpubkey_address": o["scriptPubKey"]["address"],
                        "value": btc_to_sat(&o["value"]),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    json!({
        "txid": t["txid"],
        "vin": vin,
        "vout": vout,
        "fee": t.get("fee").map(btc_to_sat).unwrap_or(0),
        "status": {
            "confirmed": true,
            "block_height": height,
            "block_time": t.get("blocktime").and_then(Value::as_u64)
                .or_else(|| t.get("time").and_then(Value::as_u64)),
        },
    })
}

fn btc_to_sat(v: &Value) -> u64 {
    (v.as_f64().unwrap_or(0.0) * 1e8).round().max(0.0) as u64
}

fn recommended_fees(rpc: &Rpc) -> Value {
    let at = |t: u16| fortis_node::estimate_feerate(rpc, t).unwrap_or(1);
    let fastest = at(1);
    let half = at(3).min(fastest);
    let hour = at(6).min(half);
    let economy = at(144).min(hour);
    json!({
        "fastestFee": fastest,
        "halfHourFee": half,
        "hourFee": hour,
        "economyFee": economy,
        "minimumFee": 1u64,
    })
}

fn address_spk(addr: &str, network: Network) -> Result<String> {
    let a = addr
        .parse::<Address<NetworkUnchecked>>()
        .map_err(|e| anyhow!("bad address {addr}: {e}"))?
        .require_network(network)
        .map_err(|_| anyhow!("address {addr} is not valid on this network"))?;
    Ok(hex::encode(a.script_pubkey().as_bytes()))
}

fn err(status: u16, msg: impl std::fmt::Display) -> Reply {
    Reply::Json(status, json!({ "error": msg.to_string() }))
}

fn read_body(req: &mut Request) -> String {
    if req.method() == &Method::Post {
        let mut s = String::new();
        let _ = req.as_reader().read_to_string(&mut s);
        s
    } else {
        String::new()
    }
}

fn respond(req: Request, reply: Reply) -> std::io::Result<()> {
    let (status, ctype, data): (u16, &str, Vec<u8>) = match reply {
        Reply::Empty(s) => (s, "text/plain", Vec::new()),
        Reply::Text(s, t) => (s, "text/plain", t.into_bytes()),
        Reply::Json(s, v) => (s, "application/json", serde_json::to_vec(&v).unwrap_or_default()),
    };
    let mut resp = Response::from_data(data).with_status_code(status);
    for (k, v) in [
        ("Access-Control-Allow-Origin", "*"),
        ("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
        ("Access-Control-Allow-Headers", "content-type"),
        ("Content-Type", ctype),
    ] {
        if let Ok(h) = Header::from_bytes(k.as_bytes(), v.as_bytes()) {
            resp.add_header(h);
        }
    }
    req.respond(resp)
}

//! Route handlers. Each returns a JSON body or an `anyhow` error (rendered as a
//! 400 with `{"error": ...}`).

use std::path::Path;

use anyhow::{anyhow, bail, Result};
use serde::Deserialize;
use serde_json::{json, Value};

use fortis_node::Rpc;
use wallet_core::{Chain, ChainParams};

use crate::state::{Connected, State};

fn conn(state: &State) -> Result<&Connected> {
    state
        .connected
        .as_ref()
        .ok_or_else(|| anyhow!("gateway is not connected — POST /v1/connect first"))
}

pub fn status(rpc: &Rpc, state: &State) -> Result<Value> {
    let node = fortis_node::chain_status(rpc)?;
    let scanning = state
        .connected
        .as_ref()
        .and_then(|c| fortis_node::scanning(rpc, &c.watch_wallet))
        .map(|(progress, duration)| json!({ "progress": progress, "duration": duration }));
    Ok(json!({
        "node": node,
        "connected": state.connected,
        "scanning": scanning,
    }))
}

#[derive(Deserialize)]
pub struct ConnectReq {
    pub chain: String,
    pub network: String,
    pub account_xpub: String,
    pub master_fingerprint: String,
    #[serde(default)]
    pub rescan: bool,
    #[serde(default = "default_range")]
    pub range: u32,
}
fn default_range() -> u32 {
    1000
}

pub fn connect(rpc: &Rpc, state: &mut State, home: &Path, req: ConnectReq) -> Result<Value> {
    let chain = match req.chain.as_str() {
        "blk" => Chain::Blk,
        "btc" => Chain::Btc,
        other => bail!("chain must be \"blk\" or \"btc\", got {other:?}"),
    };
    let params = ChainParams::resolve(chain, &req.network)
        .ok_or_else(|| anyhow!("unknown chain/network {}/{}", req.chain, req.network))?;
    let watch_wallet = format!("fortis-{}", req.chain);

    fortis_node::ensure_watch_wallet(rpc, &watch_wallet)?;
    let (recv_ok, change_ok) = fortis_node::import_account(
        rpc,
        &watch_wallet,
        &req.master_fingerprint,
        params.bip44_coin_type,
        0,
        &req.account_xpub,
        req.range,
        req.rescan,
    )?;
    if !(recv_ok && change_ok) {
        bail!("descriptor import failed on the node");
    }

    state.connected = Some(Connected {
        chain: req.chain,
        network: req.network,
        account_xpub: req.account_xpub,
        master_fingerprint: req.master_fingerprint,
        watch_wallet: watch_wallet.clone(),
    });
    state.save(home)?;

    let scanning = fortis_node::scanning(rpc, &watch_wallet)
        .map(|(progress, duration)| json!({ "progress": progress, "duration": duration }));
    Ok(json!({ "watch_wallet": watch_wallet, "imported": true, "scanning": scanning }))
}

pub fn balances(rpc: &Rpc, state: &State) -> Result<Value> {
    let b = fortis_node::wallet_balances(rpc, &conn(state)?.watch_wallet)?;
    Ok(serde_json::to_value(b)?)
}

pub fn utxos(rpc: &Rpc, state: &State, min_conf: u32) -> Result<Value> {
    let list = fortis_node::collect_utxos(rpc, &conn(state)?.watch_wallet, min_conf)?;
    let rows: Vec<Value> = list
        .iter()
        .map(|u| {
            json!({
                "txid": u.outpoint.txid.to_string(),
                "vout": u.outpoint.vout,
                "value_sat": u.value.to_sat(),
                "script_pubkey_hex": hex::encode(u.script_pubkey.as_bytes()),
                "confirmations": u.confirmations,
                "is_change": u.is_change,
                "derivation_index": u.derivation_index,
            })
        })
        .collect();
    Ok(Value::Array(rows))
}

pub fn feerate(rpc: &Rpc, conf_target: u16) -> Result<Value> {
    Ok(json!({ "sat_vb": fortis_node::estimate_feerate(rpc, conf_target.max(1))? }))
}

pub fn history(rpc: &Rpc, state: &State, count: u32) -> Result<Value> {
    let h = fortis_node::history(rpc, &conn(state)?.watch_wallet, count.clamp(1, 1000))?;
    Ok(serde_json::to_value(h)?)
}

#[derive(Deserialize)]
pub struct BroadcastReq {
    pub hex: String,
}

pub fn broadcast(rpc: &Rpc, req: BroadcastReq) -> Result<Value> {
    let txid = fortis_node::broadcast(rpc, req.hex.trim())?;
    Ok(json!({ "txid": txid.to_string() }))
}

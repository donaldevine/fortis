// Esplora / mempool.space REST backend — no node, no gateway.
//
// Esplora is address-based, so the wallet derives its own addresses (via the wasm
// `Session`) and this scans them with a gap limit. Same method surface as
// `Gateway`, so `app.js` treats the two interchangeably.

const GAP = 20; // stop scanning a branch after this many consecutive unused
const CHUNK = 8; // parallel requests per batch
const SNAP_TTL = 10_000; // ms — reuse the UTXO scan within a poll burst
const HIST_TTL = 60_000;

async function chunked(items, n, fn) {
  const out = [];
  for (let i = 0; i < items.length; i += n) {
    out.push(...(await Promise.all(items.slice(i, i + n).map(fn))));
  }
  return out;
}

export class EsploraBackend {
  constructor(url, session, network) {
    this.kind = 'esplora';
    this.base = String(url || '').replace(/\/+$/, '');
    this.session = session;
    this.network = network || 'mainnet';
    this._snap = null;
    this._snapAt = 0;
    this._hist = null;
    this._histAt = 0;
    this._fees = null;
  }

  async get(path) {
    let r;
    try {
      r = await fetch(this.base + path);
    } catch {
      throw new Error(`cannot reach the explorer at ${this.base}`);
    }
    const text = await r.text();
    if (!r.ok) throw new Error(`explorer ${r.status} on ${path}: ${text.slice(0, 120)}`);
    return text && text.trimStart()[0] !== '<' ? JSON.parse(text) : text;
  }

  // Interface parity with Gateway
  ping() {
    return this.get('/blocks/tip/height').then((h) => ({ tip: Number(h) }));
  }
  connect() {
    return Promise.resolve({ imported: true });
  }

  /** Addresses to watch: receive + change branches, each 0..counter+GAP. */
  _watchSet() {
    const { next_receive, next_change } = this.session.indices();
    const list = [];
    for (const [branch, upto] of [[0, next_receive + GAP], [1, next_change + GAP]]) {
      for (let i = 0; i < upto; i++) {
        const a = this.session.addressAt(branch, i);
        list.push({ address: a.address, spk: a.script_pubkey_hex, branch, index: i });
      }
    }
    return list;
  }

  async _refresh(force = false) {
    if (!force && this._snap && Date.now() - this._snapAt < SNAP_TTL) return this._snap;
    const addrs = this._watchSet();
    const tip = Number(await this.get('/blocks/tip/height'));
    const perAddr = await chunked(addrs, CHUNK, async (a) => {
      const utxo = await this.get(`/address/${a.address}/utxo`).catch(() => []);
      return { a, utxo: Array.isArray(utxo) ? utxo : [] };
    });
    const utxos = perAddr.flatMap(({ a, utxo }) =>
      utxo.map((u) => ({
        txid: u.txid,
        vout: u.vout,
        value_sat: u.value,
        script_pubkey_hex: a.spk,
        confirmations: u.status?.confirmed ? Math.max(1, tip - u.status.block_height + 1) : 0,
        is_change: a.branch === 1,
        derivation_index: a.index,
      })),
    );
    this._snap = { tip, utxos, addrs };
    this._snapAt = Date.now();
    return this._snap;
  }

  async status() {
    const snap = await this._refresh();
    return {
      node: {
        chain: this.network === 'regtest' ? 'regtest' : 'main',
        blocks: snap.tip,
        headers: snap.tip,
        progress: 1,
        ibd: false,
        pruned: false,
        blake2b_active: true,
        blake2b_height: null,
        subversion: 'esplora',
      },
      connected: true,
      scanning: null,
    };
  }

  async balances() {
    const { utxos } = await this._refresh();
    const sum = (f) => utxos.filter(f).reduce((s, u) => s + u.value_sat, 0);
    return {
      confirmed_sat: sum((u) => u.confirmations >= 1),
      pending_sat: sum((u) => u.confirmations < 1),
      immature_sat: 0,
    };
  }

  async utxos(minConf = 1) {
    const { utxos } = await this._refresh(true);
    return utxos.filter((u) => u.confirmations >= minConf);
  }

  async feerate(confTarget = 6) {
    if (!this._fees) {
      this._fees = await this.get('/v1/fees/recommended').catch(() => null);
    }
    const f = this._fees || {};
    const pick =
      confTarget <= 1 ? f.fastestFee : confTarget <= 6 ? f.halfHourFee : (f.economyFee ?? f.hourFee);
    return { sat_vb: Math.max(1, Math.round(pick || f.minimumFee || 1)) };
  }

  async history(count = 50) {
    const snap = await this._refresh();
    if (!this._hist || Date.now() - this._histAt > HIST_TTL) {
      const mine = new Set(snap.addrs.map((a) => a.address));
      const lists = await chunked(snap.addrs, CHUNK, (a) =>
        this.get(`/address/${a.address}/txs`).catch(() => []),
      );
      const byTxid = new Map();
      for (const list of lists) {
        for (const tx of list) {
          if (byTxid.has(tx.txid)) continue;
          const inOurs = (tx.vin || []).reduce(
            (s, v) => s + (mine.has(v.prevout?.scriptpubkey_address) ? v.prevout.value : 0),
            0,
          );
          const outs = tx.vout || [];
          const outOurs = outs.reduce(
            (s, o) => s + (mine.has(o.scriptpubkey_address) ? o.value : 0),
            0,
          );
          const delta = outOurs - inOurs;
          const send = delta < 0;
          const counterparty = send
            ? outs.find((o) => !mine.has(o.scriptpubkey_address))?.scriptpubkey_address
            : outs.find((o) => mine.has(o.scriptpubkey_address))?.scriptpubkey_address;
          byTxid.set(tx.txid, {
            txid: tx.txid,
            direction: send ? 'send' : 'receive',
            amount_sat: send ? delta + (tx.fee || 0) : delta,
            fee_sat: send ? -(tx.fee || 0) : 0,
            confirmations: tx.status?.confirmed
              ? Math.max(1, snap.tip - tx.status.block_height + 1)
              : 0,
            time: tx.status?.block_time || Math.floor(Date.now() / 1000),
            address: counterparty || null,
          });
        }
      }
      this._hist = [...byTxid.values()].sort((a, b) => b.time - a.time);
      this._histAt = Date.now();
    }
    return this._hist.slice(0, count);
  }

  async broadcast(hex) {
    let r;
    try {
      r = await fetch(this.base + '/tx', {
        method: 'POST',
        headers: { 'content-type': 'text/plain' },
        body: hex,
      });
    } catch {
      throw new Error(`cannot reach the explorer at ${this.base}`);
    }
    const text = (await r.text()).trim();
    if (!r.ok) throw new Error(text || `explorer rejected the transaction (${r.status})`);
    if (!/^[0-9a-fA-F]{64}$/.test(text)) throw new Error(text || 'unexpected broadcast response');
    this._snap = null; // reflect the spend on the next poll
    this._hist = null;
    return { txid: text.toLowerCase() };
  }
}

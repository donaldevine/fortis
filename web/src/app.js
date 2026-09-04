// fortis web wallet — controller.
//
// Phases:  onboard → pick a backend → (locked ⇄ home)
// Keys never leave wasm. The backend is either a public explorer (Esplora, e.g.
// mempool.guide) or your own node via a fortisd gateway.

import { loadState, saveState, wipeState } from './store.js';
import { Gateway } from './gateway.js';
import { EsploraBackend } from './esplora.js';
import { ensureWasm, newMnemonic, validateMnemonic, seal, unseal, Session } from './wallet.js';
import { el, mount, toast, copy, fmt, parseAmount, shortTxid, timeAgo, countUp, initParallax } from './ui.js';

const UNIT = { blk: 'BLK', btc: 'BTC' };
const DEFAULT_ESPLORA = { blk: 'https://mempool.guide/api', btc: 'https://mempool.space/api' };

let state = null; // persisted config or null
let backend = null; // Gateway | EsploraBackend | null
let session = null; // Session or null (locked)
let ui = { screen: 'main', tab: 'receive', poll: null };

start();

async function start() {
  try {
    await ensureWasm();
  } catch {
    return mount(el('div', { class: 'screen' },
      el('h1', {}, 'fortis'),
      el('p', { class: 'err' }, 'could not load the wallet engine (wallet_wasm). Build it with:'),
      el('pre', { class: 'card mono' }, 'wasm-pack build crates/wallet-wasm --target web --out-dir ../../web/pkg')));
  }
  state = migrate(await loadState());
  initParallax();
  render();
}

// v0 stored gateway_url/gateway_token flat; v1 uses state.backend = {kind,url,token}
function migrate(s) {
  if (s && s.gateway_url && !s.backend) {
    s.backend = { kind: 'gateway', url: s.gateway_url, token: s.gateway_token };
    delete s.gateway_url;
    delete s.gateway_token;
  }
  return s;
}

function syncSession() {
  if (session) session.setIndices(state.next_receive, state.next_change);
}

function makeBackend() {
  if (!state?.backend) return null;
  if (state.backend.kind === 'esplora') {
    if (!session) return null;
    syncSession();
    return new EsploraBackend(state.backend.url, session, state.network);
  }
  return new Gateway(state.backend.url, state.backend.token);
}

function render() {
  stopPolling();
  if (!state) return renderOnboard();
  if (!session) return renderLocked();
  if (!state.backend) return renderBackendPicker();
  if (!backend) backend = makeBackend();
  renderHome();
}

/* ------------------------------------------------------------------ onboard */

const SHIELD = `<svg viewBox="0 0 512 512" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M256 60 L436 126 v146 c0 114 -78 180 -180 220 C154 452 76 386 76 272 V126 Z"
        stroke="url(#bg)" stroke-width="30" stroke-linejoin="round"/>
  <path d="M200 252 h116 M236 200 v104 M266 200 v104 M208 200 h84 a34 34 0 0 1 0 66 h-84"
        stroke="#eaf0ff" stroke-width="22" stroke-linecap="round"/>
  <defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#6ea8fe"/><stop offset="1" stop-color="#b98cff"/>
  </linearGradient></defs></svg>`;

function brand(tagline) {
  return el('div', { class: 'brand' },
    el('div', { html: SHIELD }),
    el('div', { class: 'name' }, 'fortis'),
    tagline ? el('p', { class: 'center', style: 'max-width:22rem' }, tagline) : null);
}

function renderOnboard() {
  if (ui.screen === 'create') return renderCreate();
  if (ui.screen === 'restore') return renderRestore();
  mount(el('div', { class: 'screen' },
    el('div', { class: 'spacer' }),
    brand('a non-custodial wallet for Bitcoin and its BLAKE2b fork'),
    el('div', { class: 'spacer' }),
    el('button', { class: 'primary wide', onclick: () => go('create') }, 'Create a new wallet'),
    el('button', { class: 'ghost wide', onclick: () => go('restore') }, 'Restore from a recovery phrase'),
    el('div', { class: 'spacer' })));
}

function chainPicker(current = 'blk') {
  return el('select', { id: 'chain' },
    el('option', { value: 'blk', selected: current === 'blk' }, 'BLAKE2b fork (BLK)'),
    el('option', { value: 'btc', selected: current === 'btc' }, 'Bitcoin (BTC)'));
}
function networkPicker(current = 'mainnet') {
  return el('select', { id: 'network' },
    el('option', { value: 'mainnet', selected: current === 'mainnet' }, 'mainnet'),
    el('option', { value: 'regtest', selected: current === 'regtest' }, 'regtest'));
}

function renderCreate() {
  const mnemonic = ui.draftMnemonic || (ui.draftMnemonic = newMnemonic());
  const words = mnemonic.split(/\s+/);
  mount(el('div', { class: 'screen' },
    el('h2', {}, 'Your recovery phrase'),
    el('p', {}, 'Write these 24 words down on paper and keep them offline. Anyone with them controls your funds.'),
    el('ol', { class: 'words card' }, words.map((w, i) => el('li', {}, el('b', {}, i + 1), w))),
    el('label', {}, 'Chain'), chainPicker(),
    el('label', {}, 'Network'), networkPicker(),
    el('label', {}, 'Encryption password (protects the seed on this device)'),
    el('input', { id: 'pw', type: 'password', autocomplete: 'new-password' }),
    el('label', {}, 'Confirm password'),
    el('input', { id: 'pw2', type: 'password', autocomplete: 'new-password' }),
    el('label', { class: 'row', style: 'align-items:center;gap:.5rem' },
      el('input', { id: 'ack', type: 'checkbox', style: 'width:auto;flex:0' }),
      el('span', {}, "I've written the phrase down")),
    el('div', { id: 'err', class: 'err' }),
    el('div', { class: 'row' },
      el('button', { class: 'ghost', onclick: () => { ui.draftMnemonic = null; go('main'); } }, 'Back'),
      el('button', { class: 'primary', onclick: onCreate }, 'Continue')),
  ));
}

async function onCreate() {
  const pw = val('pw'), pw2 = val('pw2');
  const err = document.getElementById('err');
  if (!document.getElementById('ack').checked) return (err.textContent = 'confirm you saved the phrase');
  if (pw.length < 8) return (err.textContent = 'use a password of at least 8 characters');
  if (pw !== pw2) return (err.textContent = 'passwords do not match');
  try {
    await finishOnboard(val('chain'), val('network'), ui.draftMnemonic, '', pw);
  } catch (e) {
    err.textContent = String(e.message || e);
  }
}

function renderRestore() {
  mount(el('div', { class: 'screen' },
    el('h2', {}, 'Restore wallet'),
    el('label', {}, 'Recovery phrase (12 or 24 words)'),
    el('textarea', { id: 'phrase', rows: 3, autocapitalize: 'none', autocomplete: 'off', spellcheck: 'false' }),
    el('label', {}, 'BIP-39 passphrase (optional)'),
    el('input', { id: 'passphrase', type: 'password', autocomplete: 'off' }),
    el('label', {}, 'Chain'), chainPicker(),
    el('label', {}, 'Network'), networkPicker(),
    el('label', {}, 'Encryption password for this device'),
    el('input', { id: 'pw', type: 'password', autocomplete: 'new-password' }),
    el('div', { id: 'err', class: 'err' }),
    el('div', { class: 'row' },
      el('button', { class: 'ghost', onclick: () => go('main') }, 'Back'),
      el('button', { class: 'primary', onclick: onRestore }, 'Restore')),
  ));
}

async function onRestore() {
  const err = document.getElementById('err');
  const phrase = val('phrase').trim().replace(/\s+/g, ' ');
  const passphrase = val('passphrase');
  const pw = val('pw');
  const chain = val('chain'), network = val('network');
  if (pw.length < 8) return (err.textContent = 'password must be at least 8 characters');
  try {
    validateMnemonic(chain, network, phrase, passphrase); // throws on bad words
    await finishOnboard(chain, network, phrase, passphrase, pw);
  } catch (e) {
    err.textContent = /invalid mnemonic/i.test(String(e)) ? 'that phrase is not valid' : String(e.message || e);
  }
}

async function finishOnboard(chain, network, mnemonic, passphrase, password) {
  const { sealed, salt } = seal(mnemonic, passphrase, password);
  state = { v: 1, chain, network, sealed, salt, next_receive: 0, next_change: 0 };
  await saveState(state);
  session = new Session(chain, network, mnemonic, passphrase);
  ui.draftMnemonic = null;
  ui.screen = 'main';
  render();
}

/* ------------------------------------------------------------------ backend picker */

function renderBackendPicker() {
  // regtest has no public explorer — go straight to a local gateway
  if (state.network === 'regtest') return renderGateway();

  mount(el('div', { class: 'screen' },
    el('div', { class: 'spacer' }),
    el('h2', {}, 'How should fortis see the chain?'),
    el('div', { class: 'card stack' },
      el('h2', {}, 'Public explorer'),
      el('p', {}, 'No node. The explorer sees which addresses you look up; it can never move your funds.'),
      el('label', {}, 'Esplora API URL'),
      el('input', { id: 'esplora', value: DEFAULT_ESPLORA[state.chain] || DEFAULT_ESPLORA.blk }),
      el('div', { class: 'hint' }, "If it can't connect (the explorer may not send CORS headers), run  fortisd --esplora-proxy <that URL>  and use  http://127.0.0.1:8088/esplora  here."),
      el('div', { id: 'err', class: 'err' }),
      el('button', { class: 'primary wide', onclick: onUseEsplora }, 'Use this explorer')),
    el('div', { class: 'card stack' },
      el('h2', {}, 'Your own node'),
      el('p', {}, 'Private. Run the fortisd gateway against your Bitcoin Knots node.'),
      el('button', { class: 'ghost wide', onclick: () => { ui.screen = 'gateway'; renderGateway(); } }, 'Connect a node')),
    el('div', { class: 'spacer' }),
    el('button', { class: 'ghost', onclick: onLock }, 'Lock'),
  ));
}

async function onUseEsplora() {
  const err = document.getElementById('err');
  const url = val('esplora');
  err.textContent = 'checking…';
  try {
    const probe = new EsploraBackend(url, session, state.network);
    await probe.ping();
    state.backend = { kind: 'esplora', url: probe.base };
    await saveState(state);
    backend = probe;
    render();
  } catch (e) {
    err.textContent = String(e.message || e);
  }
}

/* ------------------------------------------------------------------ locked */

function renderLocked() {
  mount(el('div', { class: 'screen' },
    el('div', { class: 'spacer' }),
    brand(),
    el('div', { class: 'card stack' },
      el('label', {}, 'Password'),
      el('input', { id: 'pw', type: 'password', autocomplete: 'current-password', onkeydown: (e) => e.key === 'Enter' && onUnlock() }),
      el('div', { id: 'err', class: 'err' }),
      el('button', { class: 'primary wide', onclick: onUnlock }, 'Unlock')),
    el('div', { class: 'spacer' }),
    el('button', { class: 'ghost danger', onclick: onWipe }, 'Forget this wallet'),
  ));
}

async function onUnlock() {
  const err = document.getElementById('err');
  try {
    const { mnemonic, passphrase } = unseal(state.sealed, state.salt, val('pw'));
    session = new Session(state.chain, state.network, mnemonic, passphrase);
    syncSession();
    backend = makeBackend();
    render();
  } catch {
    err.textContent = 'wrong password';
  }
}

async function onWipe() {
  if (!confirm('Erase the encrypted seed from this device? Restore needs your recovery phrase.')) return;
  await wipeState();
  session?.free();
  session = null;
  state = null;
  backend = null;
  render();
}

/* ------------------------------------------------------------------ gateway (own node) */

function renderGateway() {
  const def = state.network === 'regtest' ? 'http://127.0.0.1:8090' : 'http://127.0.0.1:8088';
  const cur = state.backend?.kind === 'gateway' ? state.backend : {};
  mount(el('div', { class: 'screen' },
    el('h2', {}, 'Connect to your node'),
    el('p', {}, 'Start the fortisd gateway against your Bitcoin Knots node and paste the URL and token it prints.'),
    el('label', {}, 'Gateway URL'),
    el('input', { id: 'url', value: cur.url || def }),
    el('label', {}, 'API token'),
    el('input', { id: 'token', value: cur.token || '', autocomplete: 'off' }),
    el('div', { id: 'err', class: 'err' }),
    el('button', { class: 'primary wide', onclick: onConnectGateway }, 'Connect'),
    state.network === 'regtest' ? null
      : el('button', { class: 'ghost wide', onclick: renderBackendPicker }, 'Back'),
    el('button', { class: 'ghost wide', onclick: onLock }, 'Lock'),
  ));
}

async function onConnectGateway() {
  const err = document.getElementById('err');
  err.textContent = 'connecting…';
  const g = new Gateway(val('url'), val('token'));
  try {
    await g.ping();
    const st = await g.status();
    const nodeChain = st.node?.chain;
    if (state.network === 'mainnet' && nodeChain !== 'main') throw new Error(`node is on ${nodeChain}, wallet is mainnet`);
    if (state.network === 'regtest' && nodeChain !== 'regtest') throw new Error(`node is on ${nodeChain}, wallet is regtest`);
    await g.connect({
      chain: state.chain,
      network: state.network,
      account_xpub: session.xpub,
      master_fingerprint: session.fingerprint,
    });
    state.backend = { kind: 'gateway', url: g.url, token: g.token };
    await saveState(state);
    backend = g;
    render();
  } catch (e) {
    err.textContent = String(e.message || e);
  }
}

/* ------------------------------------------------------------------ home */

let cache = { balances: null, status: null, history: [], feerate: {} };

function renderHome() {
  const unit = UNIT[state.chain];
  const b = cache.balances;
  const st = cache.status;
  let dot = 'warn', hint = 'connecting…';
  if (st) {
    const p = st.node?.progress ?? 0;
    if (st.scanning) { dot = 'warn'; hint = `rescanning ${(st.scanning.progress * 100).toFixed(0)}%`; }
    else if (st.node?.ibd || p < 0.999) { dot = 'warn'; hint = `syncing ${(p * 100).toFixed(2)}%`; }
    else { dot = 'ok'; hint = `block ${st.node?.blocks}`; }
  }
  const via = state.backend.kind === 'esplora' ? new URL(state.backend.url).host : 'your node';

  const top = el('div', { class: 'topbar' },
    el('div', {},
      el('div', { class: 'bal' },
        el('span', { class: 'num' }, b ? fmt(b.confirmed_sat) : '—'), ' ',
        el('span', { class: 'unit' }, unit)),
      el('div', { class: 'hint' },
        el('span', { class: `dot ${dot}` }), ' ', hint, `  ·  ${via}`,
        b && b.pending_sat ? `  ·  +${fmt(b.pending_sat)} pending` : '')),
    el('button', { class: 'ghost', onclick: onLock }, 'Lock'));

  const tabs = el('div', { class: 'tabs card' },
    ['receive', 'send', 'history'].map((t) =>
      el('button', { class: ui.tab === t ? 'active' : '', onclick: () => { ui.tab = t; renderHome(); } },
        t[0].toUpperCase() + t.slice(1))));

  let body;
  if (ui.tab === 'receive') body = paneReceive();
  else if (ui.tab === 'send') body = paneSend();
  else body = paneHistory();

  mount(el('div', { class: 'screen', style: 'gap:.9rem' }, top, tabs, body));

  if (b) {
    const num = document.querySelector('.bal .num');
    if (num) countUp(num, cache._shownBal ?? 0, b.confirmed_sat);
    cache._shownBal = b.confirmed_sat;
  }
  startPolling();
}

function paneReceive() {
  const i = state.next_receive;
  let addr = '…';
  try { addr = session.receiveAddress(i).address; } catch (e) { addr = String(e.message || e); }
  return el('div', { class: 'card stack' },
    el('h2', {}, 'Receive'),
    el('div', { class: 'addr-box mono' }, addr),
    el('div', { class: 'hint' }, `address #${i}`),
    el('div', { class: 'row' },
      el('button', { onclick: () => copy(addr) }, 'Copy'),
      el('button', { class: 'ghost', onclick: async () => {
        state.next_receive += 1; syncSession(); await saveState(state); renderHome();
      } }, 'New address')));
}

function paneSend() {
  const d = ui.draft || (ui.draft = { to: '', amount: '', sweep: false, target: 6 });
  const unit = UNIT[state.chain];
  const presets = cache.feerate;

  const feeSeg = el('div', { class: 'seg' },
    [['1', 'Fast'], ['6', 'Normal'], ['144', 'Slow']].map(([t, lbl]) =>
      el('button', {
        class: String(d.target) === t ? 'on' : '',
        onclick: () => { d.target = Number(t); d.customFee = null; renderHome(); },
      }, `${lbl}${presets[t] ? ` · ${presets[t]} s/vB` : ''}`)));

  return el('div', { class: 'card stack' },
    el('h2', {}, 'Send'),
    el('label', {}, 'To address'),
    el('input', { id: 'to', value: d.to, autocapitalize: 'none', spellcheck: 'false',
      oninput: (e) => (d.to = e.target.value.trim()) }),
    el('label', { class: 'row', style: 'align-items:center;gap:.5rem' },
      el('input', { type: 'checkbox', style: 'width:auto;flex:0', checked: d.sweep,
        onchange: (e) => { d.sweep = e.target.checked; renderHome(); } }),
      el('span', {}, 'Send maximum (sweep)')),
    d.sweep ? null : el('label', {}, `Amount (${unit})`),
    d.sweep ? null : el('input', { id: 'amount', value: d.amount, inputmode: 'decimal',
      oninput: (e) => (d.amount = e.target.value) }),
    el('label', {}, 'Fee rate'),
    feeSeg,
    el('input', { id: 'customfee', placeholder: 'custom sat/vB (optional)', inputmode: 'numeric',
      value: d.customFee || '', oninput: (e) => (d.customFee = e.target.value) }),
    state.chain === 'btc' && !d.sweep
      ? el('label', { class: 'row', style: 'align-items:center;gap:.5rem' },
          el('input', { type: 'checkbox', style: 'width:auto;flex:0', checked: d.replayProtect,
            onchange: (e) => (d.replayProtect = e.target.checked) }),
          el('span', {}, 'BLK replay protection (100-byte OP_RETURN)'))
      : null,
    state.chain === 'btc' && !d.sweep && d.replayProtect
      ? el('div', { class: 'hint' }, 'Adds ~110 vB of fee. Non-standard on default Bitcoin relay — broadcast via a node/service that accepts large OP_RETURN.')
      : null,
    cache.status?.pricing
      ? el('div', { class: 'hint' }, `Service fee: ${(cache.status.pricing.bps / 100).toFixed(2)}% of the amount sent (min ${fmt(cache.status.pricing.floor_sat)} sat) — supports this hosted node.`)
      : null,
    el('div', { id: 'err', class: 'err' }),
    el('button', { class: 'primary wide', onclick: onReview }, 'Review'));
}

async function onReview() {
  const err = document.getElementById('err');
  const d = ui.draft;
  err.textContent = 'building…';
  try {
    if (!d.to) throw new Error('enter a destination address');
    const minConf = 1;
    const [feerateResp, utxos] = await Promise.all([
      d.customFee ? Promise.resolve({ sat_vb: Number(d.customFee) }) : backend.feerate(d.target),
      backend.utxos(minConf),
    ]);
    const feerate = Math.max(1, Math.round(feerateResp.sat_vb));
    if (!utxos.length) throw new Error('no confirmed coins to spend');

    session.setIndices(state.next_receive, state.next_change);
    let opReturnHex;
    if (state.chain === 'btc' && !d.sweep && d.replayProtect) {
      opReturnHex = [...crypto.getRandomValues(new Uint8Array(100))]
        .map((b) => b.toString(16).padStart(2, '0')).join('');
    }
    const pricing = cache.status?.pricing;
    const serviceFee = pricing
      ? { address: pricing.address, bps: pricing.bps, floor_sat: pricing.floor_sat, cap_sat: pricing.cap_sat }
      : undefined;
    const plan = d.sweep
      ? session.planSweep(utxos, d.to, feerate, minConf, serviceFee)
      : session.planPayment(
          utxos, [{ address: d.to, amount_sat: parseAmount(d.amount) }], feerate, minConf, opReturnHex, serviceFee,
        );

    ui.pendingPlan = { plan, feerate, to: d.to, sweep: d.sweep, replayProtect: !!opReturnHex };
    renderConfirm();
  } catch (e) {
    err.textContent = String(e.message || e).replace(/^.*?: /, '');
  }
}

function renderConfirm() {
  const { plan, feerate, to, sweep } = ui.pendingPlan;
  const unit = UNIT[state.chain];
  const inTotal = plan.selected.reduce((s, u) => s + Number(u.value_sat), 0);
  const svcFee = Number(plan.service_fee_sat || 0);
  const outAmount = inTotal - Number(plan.fee_sat) - svcFee - Number(plan.change_sat || 0);
  const cancel = () => { ui.pendingPlan = null; renderHome(); };

  mount(el('div', { class: 'sheet-wrap' },
    el('div', { class: 'scrim', onclick: cancel }),
    el('div', { class: 'sheet' },
      el('div', { class: 'grabber' }),
      el('h2', {}, sweep ? 'Confirm sweep' : 'Confirm payment'),
      el('div', { class: 'amount-lead mono' }, `${fmt(outAmount)} `, el('span', { class: 'unit' }, unit)),
      el('div', { class: 'kvs' },
        row('To', el('span', { class: 'mono', style: 'word-break:break-all;text-align:right' }, to)),
        row('Network fee', `${fmt(plan.fee_sat)} ${unit} · ${feerate} sat/vB`),
        svcFee > 0 ? row('Service fee', `${fmt(svcFee)} ${unit}`) : null,
        plan.change_sat != null ? row('Change', `${fmt(plan.change_sat)} ${unit}`) : null,
        row('From', `${plan.selected.length} input${plan.selected.length > 1 ? 's' : ''}`),
        ui.pendingPlan.replayProtect ? row('Replay protection', 'on · 100-byte OP_RETURN') : null,
        row('Total', el('b', {}, `${fmt(outAmount + Number(plan.fee_sat) + svcFee)} ${unit}`))),
      el('div', { id: 'err', class: 'err' }),
      el('div', { class: 'row' },
        el('button', { class: 'ghost', onclick: cancel }, 'Cancel'),
        el('button', { class: 'primary', id: 'send', onclick: onSend }, 'Sign & send')))));
}
const row = (k, v) => el('div', { class: 'kv' }, el('span', {}, k), v?.nodeType ? v : el('span', {}, v));

async function onSend() {
  const err = document.getElementById('err');
  const btn = document.getElementById('send');
  btn.disabled = true;
  err.textContent = 'signing…';
  try {
    const { plan } = ui.pendingPlan;
    const signed = session.sign(plan.tx_hex, plan.selected);
    err.textContent = 'broadcasting…';
    const { txid } = await backend.broadcast(signed);
    const { next_change } = session.indices();
    state.next_change = Math.max(state.next_change, next_change ?? 0);
    await saveState(state);
    ui.pendingPlan = null;
    ui.draft = null;
    ui.tab = 'history';
    toast('sent · ' + shortTxid(txid));
    await refresh();
    renderHome();
  } catch (e) {
    btn.disabled = false;
    err.textContent = String(e.message || e).replace(/^.*?: /, '');
  }
}

function paneHistory() {
  const h = cache.history;
  const unit = UNIT[state.chain];
  if (!h.length) return el('div', { class: 'card center hint' }, 'no transactions yet');
  return el('div', { class: 'card hist' }, h.map((t) => {
    const pos = t.amount_sat > 0;
    const conf = t.confirmations;
    return el('div', { class: 'item', onclick: () => copy(t.txid) },
      el('div', {},
        el('div', { class: `amt ${pos ? 'pos' : 'neg'}` }, `${pos ? '+' : ''}${fmt(t.amount_sat)} ${unit}`),
        el('div', { class: 'meta' }, `${t.direction} · ${timeAgo(t.time)} · ${shortTxid(t.txid)}`)),
      el('span', { class: `badge ${conf < 1 ? 'pending' : ''}` }, conf < 1 ? 'pending' : conf < 6 ? `${conf} conf` : 'confirmed'));
  }));
}

/* ------------------------------------------------------------------ polling */

async function refresh() {
  if (!backend) return;
  try {
    const [status, balances, history] = await Promise.all([
      backend.status(),
      backend.balances().catch(() => null),
      backend.history(50).catch(() => []),
    ]);
    cache.status = status;
    if (balances) cache.balances = balances;
    cache.history = history || [];
    if (!Object.keys(cache.feerate).length) {
      for (const t of [1, 6, 144]) {
        backend.feerate(t).then((r) => { cache.feerate[t] = r.sat_vb; renderHomeIfIdle(); }).catch(() => {});
      }
    }
  } catch {
    cache.status = null;
  }
}

function renderHomeIfIdle() {
  if (session && state?.backend && !ui.pendingPlan) renderHome();
}

function startPolling() {
  if (ui.poll) return;
  const every = state.backend?.kind === 'esplora' ? 25_000 : 15_000;
  refresh().then(renderHomeIfIdle);
  ui.poll = setInterval(() => refresh().then(renderHomeIfIdle), every);
}
function stopPolling() {
  if (ui.poll) clearInterval(ui.poll);
  ui.poll = null;
}

/* ------------------------------------------------------------------ misc */

function onLock() {
  session?.free();
  session = null;
  backend = null;
  render();
}
function go(screen) {
  ui.screen = screen;
  render();
}
function val(id) {
  return (document.getElementById(id)?.value ?? '').trim();
}

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}

// Tiny DOM helpers — no framework.

export function el(tag, props = {}, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k === 'html') n.innerHTML = v;
    else if (k === 'value') n.value = v;
    else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2), v);
    else n.setAttribute(k, v === true ? '' : String(v));
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    n.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return n;
}

export function mount(node) {
  document.getElementById('app').replaceChildren(node);
}

export function toast(msg, ms = 2600) {
  const t = el('div', { class: 'toast' }, msg);
  document.body.append(t);
  setTimeout(() => t.remove(), ms);
}

export async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast('copied');
  } catch {
    toast('copy failed');
  }
}

export const SAT = 100_000_000;
export const fmt = (sat) => (Number(sat) / SAT).toFixed(8);
export const parseAmount = (s) => {
  const n = Number(String(s).trim());
  if (!isFinite(n) || n < 0) throw new Error('enter a valid amount');
  return Math.round(n * SAT);
};

export function shortTxid(txid) {
  return txid.slice(0, 10) + '…' + txid.slice(-6);
}

export function timeAgo(unixSecs) {
  if (!unixSecs) return '';
  const s = Math.max(0, Math.floor(Date.now() / 1000 - unixSecs));
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

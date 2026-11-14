// Rendering helpers. Every dynamic string goes through esc(): no HTML injection from book data.
export const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export const icons = {
  back: '<svg class="i" viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg>',
  cam: '<svg class="i" viewBox="0 0 24 24"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>',
  barcode: '<svg class="i" viewBox="0 0 24 24"><path d="M4 6v12M7 6v12M10 6v12M14 6v12M16 6v12M20 6v12"/></svg>',
  book: '<svg class="i" viewBox="0 0 24 24"><path d="M5 4h11a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2z"/><path d="M5 18a2 2 0 0 1 2-2h11"/></svg>',
  shelf: '<svg class="i" viewBox="0 0 24 24"><path d="M3 20h18M5 20V6M8 20V8M11 20V5M15 20l-2-13 3-.5 2.5 13.5"/></svg>',
  pen: '<svg class="i" viewBox="0 0 24 24"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>',
  plus: '<svg class="i" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
  search: '<svg class="i" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.2-4.2"/></svg>',
  more: '<svg class="i" viewBox="0 0 24 24"><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></svg>',
};

const PALETTE = ['#3d5a80', '#8a3b2e', '#2f5d50', '#6b4f7a', '#9a6b1f', '#38495a', '#7a2e4a', '#4f6b2f', '#5a3d2b', '#2e5a7a'];
export function colorFor(text) {
  let h = 0;
  for (const ch of String(text)) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

/** Real cover if we have one, otherwise a typographic cover in a stable colour. */
export function cover(b, { lazy = true } = {}) {
  if (b.cover) {
    return `<div class="cover"><img src="${esc(b.cover)}" alt="" ${lazy ? 'loading="lazy"' : ''} decoding="async"></div>`;
  }
  const author = (b.authors ?? [])[0] ?? '';
  return `<div class="cover"><div class="typo" data-vars='${esc(JSON.stringify({ '--c': colorFor(b.title + author) }))}'><b>${esc(b.title)}</b><small>${esc(author)}</small></div></div>`;
}

/** CSP forbids inline style attributes, so CSS variables travel as data-vars and are applied here. */
export function applyVars(root) {
  root.querySelectorAll('[data-vars]').forEach(el => {
    const vars = JSON.parse(el.dataset.vars);
    for (const [k, v] of Object.entries(vars)) el.style.setProperty(k, v);
  });
}

export const surname = b => (b.authorSort ?? '').split(',')[0] || '';
export const euros = cents => cents == null ? '–' : (cents / 100).toLocaleString('de-DE', { style: 'currency', currency: 'EUR' });
export const authors = list => (list ?? []).join(', ');

let toastTimer;
export function toast(message, isError = false) {
  document.querySelector('.toast')?.remove();
  const el = document.createElement('div');
  el.className = 'toast' + (isError ? ' err' : '');
  el.setAttribute('role', isError ? 'alert' : 'status');
  el.textContent = message;
  document.body.append(el);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), isError ? 6000 : 3000);
}

export const pendingView = text => `<div class="pending"><div class="spinner"></div><p>${esc(text)}</p></div>`;

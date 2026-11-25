// Rendering helpers. Every dynamic string goes through esc(): no HTML injection from book data.
export const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const svg = d => `<svg class="i" viewBox="0 0 24 24" aria-hidden="true">${d}</svg>`;
// Lucide shapes, drawn with the stroke settings from styles.css (2 px, square caps).
export const icons = {
  back: svg('<path d="m15 18-6-6 6-6"/>'),
  next: svg('<path d="m9 18 6-6-6-6"/>'),
  cam: svg('<path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z"/><circle cx="12" cy="13" r="3"/>'),
  barcode: svg('<path d="M3 5v14M8 5v14M12 5v14M17 5v14M21 5v14"/>'),
  book: svg('<path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"/>'),
  shelf: svg('<path d="m16 6 4 14M12 6v14M8 8v12M4 4v16"/>'),
  pen: svg('<path d="M21.17 6.81a2.82 2.82 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z"/>'),
  plus: svg('<path d="M5 12h14M12 5v14"/>'),
  minus: svg('<path d="M5 12h14"/>'),
  keyboard: svg('<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/>'),
  search: svg('<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>'),
  refresh: svg('<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>'),
  check: svg('<path d="M20 6 9 17l-5-5"/>'),
  x: svg('<path d="M18 6 6 18M6 6l12 12"/>'),
  share: svg('<path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13"/>'),
  bookmark: svg('<path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/>'),
  user: svg('<circle cx="12" cy="8" r="5"/><path d="M20 21a8 8 0 0 0-16 0"/>'),
  sun: svg('<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/>'),
  moon: svg('<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>'),
};

const PALETTE = ['#3d5a80', '#8a3b2e', '#2f5d50', '#6b4f7a', '#9a6b1f', '#38495a', '#7a2e4a', '#4f6b2f', '#5a3d2b', '#2e5a7a'];
export function colorFor(text) {
  let h = 0;
  for (const ch of String(text)) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

/**
 * The one cover component for every list: our stored cover, else the cover of the edition by ISBN (fetched and kept
 * by the server), else a typographic cover in a stable colour. The typographic one lies underneath and shows when
 * the image fails; while the image loads, the box is the skeleton.
 */
export const coverSrc = b => b.cover ?? (b.isbn13 ? `/api/covers/${b.isbn13}` : null);

export function cover(b, { lazy = true } = {}) {
  const src = coverSrc(b);
  const author = (b.authors ?? [])[0] ?? '';
  const typo = `<div class="typo" data-vars='${esc(JSON.stringify({ '--c': colorFor(b.title + author) }))}'><b>${esc(b.title)}</b><small>${esc(author)}</small></div>`;
  return `<div class="cover">${typo}${src ? `<img src="${esc(src)}" alt="" ${lazy ? 'loading="lazy"' : ''} decoding="async">` : ''}</div>`;
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

// ---------------------------------------------------------------- Hell/Dunkel

const isDark = () => document.documentElement.dataset.theme
  ? document.documentElement.dataset.theme === 'dark'
  : matchMedia('(prefers-color-scheme: dark)').matches;

/** Round button for the page head. The choice is stored; without one the system setting applies. */
export const themeButton = () => `<button class="round" data-theme-toggle aria-label="${isDark() ? 'Helles' : 'Dunkles'} Design">${isDark() ? icons.sun : icons.moon}</button>`;

export function toggleTheme() {
  const next = isDark() ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem('shelved.theme', next); } catch { /* not stored, still switched */ }
  document.querySelectorAll('meta[name="theme-color"]').forEach(m => { m.content = next === 'dark' ? '#161616' : '#fdfdfd'; });
  document.querySelectorAll('[data-theme-toggle]').forEach(b => {
    b.innerHTML = isDark() ? icons.sun : icons.moon;
    b.setAttribute('aria-label', isDark() ? 'Helles Design' : 'Dunkles Design');
  });
}

// ---------------------------------------------------------------- Dialog

/**
 * Modal with a title, free HTML and buttons (left aligned, primary first). Resolves with the value of the
 * pressed button, or null when closed with Esc or the cross.
 */
export function dialog({ title, body, buttons }) {
  return new Promise(resolve => {
    const d = document.createElement('dialog');
    d.className = 'sheet';
    d.innerHTML = `<button class="round sm close" value="" aria-label="Schließen">${icons.x}</button>
      <h2>${esc(title)}</h2>${body}
      <div class="actions">${buttons.map(b => `<button class="btn ${b.kind ?? ''}" value="${esc(b.value)}">${esc(b.label)}</button>`).join('')}</div>`;
    let result = null;
    d.addEventListener('click', e => {
      const b = e.target.closest('button[value]');
      if (!b) return;
      result = b.value || null;
      d.close();
    });
    d.addEventListener('close', () => { d.remove(); resolve(result === null ? null : { value: result, root: d }); });
    document.body.append(d);
    applyVars(d);
    d.showModal();
    d.querySelector('.actions .btn')?.focus();
  });
}

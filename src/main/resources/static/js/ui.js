// Rendering helpers. Every dynamic string goes through esc(): no HTML injection from book data.
export const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const svg = d => `<svg class="i" viewBox="0 0 24 24" aria-hidden="true">${d}</svg>`;
// Lucide shapes, drawn with the stroke settings from styles.css (2 px, square caps).
export const icons = {
  back: svg('<path d="m15 18-6-6 6-6"/>'),
  next: svg('<path d="m9 18 6-6-6-6"/>'),
  cam: svg('<path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z"/><circle cx="12" cy="13" r="3"/>'),
  book: svg('<path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"/>'),
  pen: svg('<path d="M21.17 6.81a2.82 2.82 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z"/>'),
  plus: svg('<path d="M5 12h14M12 5v14"/>'),
  minus: svg('<path d="M5 12h14"/>'),
  keyboard: svg('<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/>'),
  search: svg('<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>'),
  check: svg('<path d="M20 6 9 17l-5-5"/>'),
  x: svg('<path d="M18 6 6 18M6 6l12 12"/>'),
  image: svg('<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.09-3.09a2 2 0 0 0-2.82 0L6 21"/>'),
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
// Google's volume id, for hits without ISBN (self-published titles); its cover URL is never loaded directly (CSP)
const googleId = url => /books\.google\.[a-z.]+\/books\/content\?.*\bid=([\w-]{6,20})/.exec(url ?? '')?.[1];

export const coverSrc = b => b.cover
  ?? (b.isbn13 ? `/api/covers/${b.isbn13}?v=f2` : googleId(b.coverUrl) ? `/api/covers/google/${googleId(b.coverUrl)}` : null);

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

/** Loading: a row of spines, one after another pulled out a little, as if someone ran a finger along the shelf. */
export const pendingView = text => `<div class="pending" role="status"><div class="seeker" aria-hidden="true">${'<i></i>'.repeat(9)}</div><p>${esc(text)}</p></div>`;

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

// ---------------------------------------------------------------- 3D-Buch

const EAN_L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011'];
const EAN_PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];

/** The EAN-13 bars of an ISBN, as printed on every back cover. */
export function ean13(isbn) {
  if (!/^\d{13}$/.test(isbn ?? '')) return '';
  const d = [...isbn].map(Number);
  const r = c => [...c].map(b => b === '1' ? '0' : '1').join('');
  let bits = '101';
  for (let i = 1; i <= 6; i++) bits += EAN_PARITY[d[0]][i - 1] === 'L' ? EAN_L[d[i]] : [...r(EAN_L[d[i]])].reverse().join('');
  bits += '01010';
  for (let i = 7; i <= 12; i++) bits += r(EAN_L[d[i]]);
  bits += '101';
  const guard = i => i < 3 || (i >= 45 && i < 50) || i >= 92;
  const bars = [...bits].map((b, i) => b === '1' ? `<rect x="${i}" width="1" height="${guard(i) ? 40 : 36}"/>` : '').join('');
  return `<svg class="ean" viewBox="0 0 95 40" preserveAspectRatio="none" aria-hidden="true">${bars}</svg>`;
}

const SPRAYED = /farbschnitt|farbige[mnr]? (buch)?schnitt|(sprayed|stained|painted|colou?red) edges/i;
const HEIGHT_MM = { HARDCOVER: 215, PAPERBACK: 187 };

/**
 * The book as an object: the cover in front, the real back if someone photographed it (else one set from the blurb
 * and the ISBN), the spine, and the page block in its real thickness, coloured when the edges are sprayed.
 * Drag to turn it, tap to flip it over. Colours for spine, back and a guessed Farbschnitt come from the cover.
 */
export function book3d(b, { thicknessMm, backCover = null, spine = null, edgePhoto = null, edgeColor = null } = {}) {
  const pages = b.pageCount ? b.pageCount * 0.1 + (b.format === 'HARDCOVER' ? 6 : 1) : 24;
  const t = Math.max(4, Math.round(thicknessMm ?? pages));
  const heightMm = b.heightMm ?? HEIGHT_MM[b.format] ?? 200;
  const sprayed = edgeColor && edgeColor !== 'none' ? edgeColor
    : edgeColor === 'none' ? null : [b.title, b.subtitle, b.description].some(s => SPRAYED.test(s ?? '')) ? 'auto' : null;
  const author = (b.authors ?? [])[0] ?? '';
  // without an own photo: the Farbschnitt shown on the publisher's mockup (wishes and editions; books get it from the server)
  edgePhoto ??= 'edgePhoto' in b || edgeColor ? null : (coverSrc(b) ?? '').replace(/^(\/api\/(?:wishes\/[\w-]+|covers\/\d{13}))(?:\/cover)?\?/, '$1/edge?');
  if (edgePhoto && !/\/edge/.test(edgePhoto)) edgePhoto = null;
  const back = backCover
    ? `<img src="${esc(backCover)}" alt="" decoding="async">`
    : `<div class="gen">${b.description ? `<p>${esc(b.description.slice(0, 700))}</p>` : `<p class="t">${esc(b.title)}</p>`}
        ${b.isbn13 ? `<div class="code">${ean13(b.isbn13)}<span>${esc(b.isbn13)}</span></div>` : ''}</div>`;
  const vars = { '--ratio': (t / heightMm).toFixed(4), '--inset': b.format === 'HARDCOVER' ? '2px' : '0px' };
  if (sprayed && sprayed !== 'auto') vars['--edge'] = sprayed;
  return `<div class="b3d" tabindex="0" role="img" aria-label="${esc(b.title)} als 3D-Buch, zum Drehen ziehen, zum Umdrehen tippen"
      data-vars='${esc(JSON.stringify(vars))}' ${sprayed === 'auto' ? 'data-edge="auto"' : ''}>
    <div class="body">
      <div class="face front">${cover(b, { lazy: false })}</div>
      <div class="face back">${back}</div>
      <div class="face spine">${spine ? `<img src="${esc(spine)}" alt="" decoding="async">`
        : `<span><b>${esc(b.title)}</b>${author ? ` <small>${esc(author)}</small>` : ''}</span>`}</div>
      <div class="face fore">${edgePhoto ? `<img src="${esc(edgePhoto)}" alt="" decoding="async">` : ''}</div><div class="face top"></div><div class="face bottom"></div>
    </div></div>`;
}

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const hex = ([r, g, b]) => '#' + [r, g, b].map(v => Math.round(v).toString(16).padStart(2, '0')).join('');
const ink = ([r, g, b]) => (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 > 0.55 ? '#161616' : '#f6f6f6';

/** Spine (the cover's left edge), back (its average) and the strongest colour (a guessed Farbschnitt). */
function coverColours(img) {
  const c = document.createElement('canvas');
  c.width = 32; c.height = 48;
  const g = c.getContext('2d', { willReadFrequently: true });
  g.drawImage(img, 0, 0, 32, 48);
  const px = g.getImageData(0, 0, 32, 48).data;
  const avg = (x0, x1) => {
    const s = [0, 0, 0]; let n = 0;
    for (let y = 0; y < 48; y++) for (let x = x0; x < x1; x++) { const i = (y * 32 + x) * 4; s[0] += px[i]; s[1] += px[i + 1]; s[2] += px[i + 2]; n++; }
    return s.map(v => v / n);
  };
  let best = null, bestScore = 0;
  for (let i = 0; i < px.length; i += 4) {
    const [r, g2, b] = [px[i], px[i + 1], px[i + 2]];
    const max = Math.max(r, g2, b), min = Math.min(r, g2, b);
    const score = (max - min) * (1 - Math.abs(max + min - 255) / 255);
    if (score > bestScore) { bestScore = score; best = [r, g2, b]; }
  }
  return { spine: avg(0, 2), back: avg(0, 32), strong: bestScore > 40 ? best : avg(0, 32) };
}

export function mountBook3d(root = document) {
  root.querySelectorAll('.b3d:not([data-live])').forEach(el => {
    el.dataset.live = '';
    const front = el.querySelector('.front img');
    const size = () => {
      const h = el.clientHeight;
      const aspect = front?.naturalWidth ? clamp(front.naturalWidth / front.naturalHeight, 0.55, 0.85) : 2 / 3;
      el.style.setProperty('--w', `${Math.round(h * aspect)}px`);
      el.style.setProperty('--d', `${Math.max(6, Math.round(h * Number(getComputedStyle(el).getPropertyValue('--ratio'))))}px`);
    };
    const colour = () => {
      if (!front?.naturalWidth) return;
      try {
        const c = coverColours(front);
        el.style.setProperty('--spine', hex(c.spine));
        el.style.setProperty('--spine-ink', ink(c.spine));
        el.style.setProperty('--back', hex(c.back));
        el.style.setProperty('--back-ink', ink(c.back));
        if (el.dataset.edge === 'auto' && !el.querySelector('.fore img')) el.style.setProperty('--edge', hex(c.strong));
      } catch { /* a cover from elsewhere taints the canvas; the CSS defaults stay */ }
    };
    // a photographed Farbschnitt: its average colour tints head and tail of the page block
    const edgeImg = el.querySelector('.fore img');
    const edgeTint = () => { try { el.style.setProperty('--edge', hex(coverColours(edgeImg).back)); } catch { /* keep default */ } };
    if (edgeImg) {
      if (edgeImg.complete && edgeImg.naturalWidth) edgeTint(); else edgeImg.addEventListener('load', edgeTint, { once: true });
      edgeImg.addEventListener('error', () => edgeImg.remove(), { once: true }); // no mockup: paper or Farbschnitt colour
    }
    const ready = () => { size(); colour(); };
    if (front?.complete && front.naturalWidth) ready(); else { size(); front?.addEventListener('load', ready, { once: true }); }

    let ry = -28, rx = -6, drag = null;
    const set = () => { el.style.setProperty('--ry', `${ry}deg`); el.style.setProperty('--rx', `${rx}deg`); };
    set();
    el.addEventListener('pointerdown', e => {
      drag = { x: e.clientX, y: e.clientY, ry, rx, moved: 0 };
      el.setPointerCapture(e.pointerId);
      el.classList.add('dragging');
    });
    el.addEventListener('pointermove', e => {
      if (!drag) return;
      const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
      drag.moved = Math.max(drag.moved, Math.hypot(dx, dy));
      ry = drag.ry + dx * 0.6;
      rx = clamp(drag.rx - dy * 0.3, -30, 30);
      set();
    });
    const end = () => {
      if (!drag) return;
      el.classList.remove('dragging');
      if (drag.moved < 6) { ry += 180; set(); }
      drag = null;
    };
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);
    el.addEventListener('keydown', e => {
      const step = { ArrowLeft: -20, ArrowRight: 20 }[e.key];
      if (step) { ry += step; set(); e.preventDefault(); }
      if (e.key === 'Enter' || e.key === ' ') { ry += 180; set(); e.preventDefault(); }
    });
  });
}

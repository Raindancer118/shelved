import { api, get, post, put, del, upload, poll, PUBLIC } from './api.js';
import { esc, icons, cover, applyVars, surname, euros, authors, toast, pendingView, colorFor, themeButton, toggleTheme, dialog, coverSrc } from './ui.js';
import { liveCamera, cameraAvailable, prepareImage, capturePhoto, pickPhotos, barcodeTracker } from './camera.js';

const app = document.getElementById('app');
const state = { me: null, books: null, wishes: null, sort: 'author', query: '' };
let cleanup = null; // stops a running camera when the view changes

// ---------------------------------------------------------------- Router

const routes = [
  [/^\/$/, viewLibrary, 'library'],
  [/^\/book\/([\w-]+)$/, viewBook, 'library'],
  [/^\/book\/([\w-]+)\/edit$/, viewEdit, 'library'],
  [/^\/new$/, viewEdit, 'library'],
  [/^\/(add|add\/barcode|add\/manual|lookup)$/, () => viewFind('add'), 'library'],
  [/^\/add\/shelf$/, viewShelfScan, 'library'],
  [/^\/scan\/([\w-]+)$/, viewScan, 'library'],
  [/^\/shelves$/, viewShelves, 'shelves'],
  [/^\/shelves\/new$/, viewShelfForm, 'shelves'],
  [/^\/shelves\/([\w-]+)\/edit$/, viewShelfForm, 'shelves'],
  [/^\/stats$/, viewStats, 'stats'],
  [/^\/wishes$/, viewWishes, 'wishes'],
  [/^\/wishes\/(add|barcode|search)$/, () => viewFind('wish'), 'wishes'],
  [/^\/login$/, viewLogin, null],
  [/^\/register$/, viewRegister, null],
  [/^\/verify$/, viewVerify, null],
  [/^\/forgot$/, viewForgot, null],
  [/^\/reset$/, viewReset, null],
  [/^\/invite$/, viewInvite, null],
  [/^\/account$/, viewAccount, null],
];

async function route() {
  cleanup?.();
  cleanup = null;
  const full = location.hash.replace(/^#/, '') || '/';
  const path = full.split('?')[0];
  if (!state.me && !PUBLIC.test(full)) {
    // not logged in (or not yet known): the login page, coming back here afterwards
    try { state.me = await get('/api/me'); } catch { return; }
  }
  document.body.classList.toggle('auth', PUBLIC.test(full));
  const [re, view, tab] = routes.find(([r]) => r.test(path)) ?? routes[0];
  const args = (path.match(re) ?? []).slice(1).map(decodeURIComponent);
  document.querySelectorAll('.tabbar a').forEach(a => a.classList.toggle('on', a.dataset.tab === tab));
  app.classList.toggle('wide', WIDE.some(r => r.test(path)));
  const render = async () => {
    const sk = skeletonFor(path);
    clearTimeout(skeletonTimer);
    if (sk) skeletonTimer = setTimeout(() => { app.innerHTML = `<div class="skeleton" aria-busy="true" aria-label="Lädt">${sk}</div>`; }, 150);
    try {
      await view(...args);
    } catch (e) {
      app.innerHTML = `<div class="empty"><h2>Das hat nicht geklappt</h2><p>${esc(e.message)}</p><a class="btn ghost" href="#/">Zur Bibliothek</a></div>`;
    }
    applyVars(app);
  };
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (document.startViewTransition && !reduce && !document.hidden && app.childElementCount) {
    // The transition only runs its callback on the next rendered frame, which never comes in background tabs.
    // Content must not depend on that, so render after 400 ms at the latest.
    let started = false;
    const once = () => { if (!started) { started = true; return render(); } };
    const t = document.startViewTransition(once);
    t.ready.catch(() => {});
    t.finished.catch(() => {});
    await new Promise(resolve => setTimeout(resolve, 400)).then(once);
    await t.updateCallbackDone.catch(() => {});
  } else {
    await render();
  }
  window.scrollTo(0, 0);
}

// Overviews use the full width; reading and forms keep a measure.
const WIDE = [/^\/$/, /^\/wishes$/, /^\/shelves$/, /^\/stats$/];

function go(path) { location.hash = '#' + path; }

/** Yes/no question as a dialog. Resolves true for the first button. */
async function ask(title, text, yes, no = 'Abbrechen', danger = false) {
  const r = await dialog({ title, body: text ? `<p>${esc(text)}</p>` : '',
    buttons: [{ label: yes, value: 'yes', kind: danger ? 'danger solid' : '' }, { label: no, value: 'no', kind: 'ghost' }] });
  return r?.value === 'yes';
}

/**
 * Runs an "add" call. If the library already has the book, asks whether this is another copy
 * and repeats the call with anotherCopy. Returns the book, or null if the user said no.
 */
async function addOrCopy(call) {
  try {
    return await call(false);
  } catch (e) {
    if (e.status !== 409) throw e;
    if (!await ask(`„${e.data.title}“ hast du schon`, 'Besitzt du es mehrfach? Dann zählt es als weiteres Exemplar.', 'Weiteres Exemplar', 'Zum Buch')) {
      go(`/book/${e.data.existingId}`);
      return null;
    }
    return call(true);
  }
}
function paint(html) { clearTimeout(skeletonTimer); app.innerHTML = html; applyVars(app); }

// Shown when a view takes longer than a blink: the shape of what is coming, no spinner.
let skeletonTimer;
const skLine = (cls = '') => `<div class="sk sk-line ${cls}"></div>`;
const SKELETONS = {
  grid: () => `<div class="sk-head">${skLine('h')}${skLine('short')}</div><div class="sk sk-field"></div>
    <div class="grid gap-top">${Array.from({ length: 9 }, () => `<div><div class="cover"></div>${skLine()}${skLine('short')}</div>`).join('')}</div>`,
  list: () => `<div class="sk-head">${skLine('h')}${skLine('short')}</div>
    <div class="list">${Array.from({ length: 6 }, () => `<div class="row"><div class="cover"></div><div class="grow">${skLine()}${skLine('short')}</div></div>`).join('')}</div>`,
  detail: () => `<div class="bar"><span class="round"></span></div><div class="stage"><div class="cover"></div></div>
    <div class="sk-head">${skLine('h')}${skLine('short')}</div><div class="list">${Array.from({ length: 5 }, () => `<div class="row">${skLine()}</div>`).join('')}</div>`,
};
function skeletonFor(path) {
  if (path === '/') return SKELETONS.grid();
  if (/^\/book\//.test(path)) return SKELETONS.detail();
  if (/^\/(wishes|shelves|stats)$/.test(path)) return SKELETONS.list();
  return null;
}

/** Hands the service worker every cover the library and wish list show, so it can keep them on the device. */
let warmed = '';
async function warmCovers() {
  const sw = navigator.serviceWorker?.controller;
  if (!sw) return;
  const [list, wish] = await Promise.all([books(), wishes()]).catch(() => [[], []]);
  const urls = [...list, ...wish].map(coverSrc).filter(Boolean).sort();
  const key = urls.join();
  if (key === warmed) return;
  warmed = key;
  sw.postMessage({ type: 'warm', urls });
}
const forgetCovers = () => { warmed = ''; navigator.serviceWorker?.controller?.postMessage({ type: 'clear' }); };
const backBar = (href, right = '') => `<div class="bar"><a class="round" href="${href}" aria-label="Zurück">${icons.back}</a>${right}</div>`;

async function books(force = false) {
  if (!state.books || force) state.books = await get('/api/books?sort=author');
  return state.books;
}
const invalidate = () => { state.books = null; state.wishes = null; };

async function wishes(force = false) {
  if (!state.wishes || force) state.wishes = await get('/api/wishes');
  return state.wishes;
}

/**
 * After books came into the library: if some of them were on the wish list, ask whether they may leave it.
 * Unticked ones stay and are not asked about again.
 */
async function askAboutWishes() {
  const done = await get('/api/wishes/fulfilled').catch(() => []);
  if (!done.length) return;
  const one = done.length === 1;
  // the cover of the book that just arrived; the wish itself often has none
  const art = w => cover({ ...w, cover: w.ownedCover ?? w.cover });
  const r = await dialog({
    title: 'Von der Wunschliste nehmen?',
    body: one
      ? `<p>Das Buch steht jetzt in deiner Bibliothek.</p>
         <div class="list"><div class="row">${art(done[0])}<div class="grow"><b>${esc(done[0].title)}</b><small>${esc(wishMeta(done[0]))}</small></div></div></div>`
      : `<p>Diese ${done.length} Bücher stehen jetzt in deiner Bibliothek.</p>
         <div class="list">${done.map(w => `<label class="row"><input class="check" type="checkbox" data-wish="${w.id}" checked>${art(w)}
           <div class="grow"><b>${esc(w.title)}</b><small>${esc(wishMeta(w))}</small></div></label>`).join('')}</div>`,
    buttons: [{ label: one ? 'Von der Liste nehmen' : 'Angehakte entfernen', value: 'remove' }, { label: 'Auf der Liste lassen', value: 'keep', kind: 'ghost' }],
  });
  if (!r) return; // closed without answer: ask again next time
  const ticked = one ? new Set([done[0].id])
    : new Set([...r.root.querySelectorAll('[data-wish]:checked')].map(c => c.dataset.wish));
  const remove = r.value === 'remove' ? done.filter(w => ticked.has(w.id)).map(w => w.id) : [];
  const keep = done.map(w => w.id).filter(id => !remove.includes(id));
  await post('/api/wishes/resolve', { remove, keep });
  state.wishes = null;
  if (remove.length) toast(remove.length === 1 ? 'Von der Wunschliste genommen' : `${remove.length} von der Wunschliste genommen`);
}

/** Head of a main view: title, one line of context, theme switch and whatever the view adds. */
const viewHead = (title, sub = '', extra = '') => `<div class="head"><div><h1>${esc(title)}</h1>${sub ? `<small>${sub}</small>` : ''}</div>
  <div class="tools">${extra}${themeButton()}</div></div>`;

// ---------------------------------------------------------------- Bibliothek

const SORTS = { author: 'Nachname A–Z', title: 'Titel', added: 'Zuletzt hinzugefügt' };

function sorted(list) {
  if (state.sort === 'title') return [...list].sort((a, b) => a.title.localeCompare(b.title, 'de'));
  if (state.sort === 'added') return [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return list; // server order is library order
}

function filtered(list) {
  const q = state.query.trim().toLowerCase();
  if (!q) return list;
  const digits = q.replace(/[^0-9x]/g, '');
  const words = q.split(/\s+/);
  return list.filter(b => {
    const hay = [b.title, ...(b.authors ?? [])].join(' ').toLowerCase();
    return words.every(w => hay.includes(w)) || (digits.length >= 10 && (b.isbn13 ?? '').includes(digits));
  });
}

function gridHtml(list) {
  if (!list.length) return `<p class="empty">Nichts gefunden.</p>`;
  return `<div class="grid">${list.map(b => `
    <a class="book" href="#/book/${b.id}" data-letter="${esc(surname(b).charAt(0).toUpperCase())}">
      <div class="cv-wrap">${cover(b)}${b.copies > 1 ? `<span class="copies">${b.copies}×</span>` : ''}</div>
      <b>${esc(b.title)}</b><small>${esc(surname(b) || authors(b.authors))}</small>
    </a>`).join('')}</div>`;
}

async function viewLibrary() {
  const [list, shelves] = await Promise.all([books(), get('/api/shelves')]);
  const name = state.me?.name || state.me?.email || '';
  const avatar = `<a class="round avatar" href="#/account" title="${esc(name)}" aria-label="Konto und Bibliothek">${name ? esc(name.charAt(0).toUpperCase()) : icons.user}</a>`;
  if (!list.length) {
    paint(`
      ${viewHead('Bibliothek', 'Noch leer', avatar)}
      <div class="empty"><h2>Fang mit einem Regalbrett an</h2>
        <p>Foto vom Regal, Barcode scannen oder von Hand: shelved holt die Daten aus DNB, Google Books und Wikidata.</p>
        <a class="btn" href="#/add">${icons.plus} Bücher hinzufügen</a></div>`);
    return;
  }
  const letters = [...new Set(list.map(b => surname(b).charAt(0).toUpperCase()).filter(l => /[A-ZÄÖÜ]/.test(l)))];
  paint(`
    ${viewHead('Bibliothek', `${esc(state.me?.library?.name ?? '')} · ${list.length} Bücher${shelves.length ? ` in ${shelves.length} ${shelves.length === 1 ? 'Regal' : 'Regalen'}` : ''}`, avatar)}
    <div class="search" role="search">${icons.search}
      <input id="q" type="search" placeholder="Titel, Autor oder ISBN" value="${esc(state.query)}" autocomplete="off" aria-label="Bibliothek durchsuchen">
      <a class="cam" href="#/add" aria-label="Scannen: nachschauen oder hinzufügen" title="Scannen">${icons.cam}</a></div>
    <div class="chips">${Object.entries(SORTS).map(([k, v]) => `<button class="chip ${state.sort === k ? 'on' : ''}" data-sort="${k}">${v}</button>`).join('')}</div>
    <div id="results">${gridHtml(filtered(sorted(list)))}</div>
    ${state.sort === 'author' && !state.query ? `<div class="index" aria-label="Springe zu Buchstabe">${letters.map(l => `<button data-letter="${esc(l)}">${esc(l)}</button>`).join('')}</div>` : ''}
`);

  warmCovers();
  const results = document.getElementById('results');
  document.getElementById('q').addEventListener('input', e => {
    state.query = e.target.value;
    results.innerHTML = gridHtml(filtered(sorted(list)));
    applyVars(results);
  });
  app.querySelectorAll('[data-sort]').forEach(b => b.addEventListener('click', () => { state.sort = b.dataset.sort; viewLibrary().then(() => applyVars(app)); }));
  app.querySelectorAll('.index button').forEach(b => b.addEventListener('click', () =>
    app.querySelector(`.book[data-letter="${CSS.escape(b.dataset.letter)}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })));
}

// ---------------------------------------------------------------- Buch

const FORMATS = { PAPERBACK: 'Taschenbuch', HARDCOVER: 'Hardcover', OTHER: 'Sonstiges' };
const LANGS = { ger: 'Deutsch', eng: 'Englisch', fre: 'Französisch', spa: 'Spanisch', ita: 'Italienisch', dut: 'Niederländisch' };

async function viewBook(id) {
  const [b, plan] = await Promise.all([get(`/api/books/${id}`), get('/api/placement')]);
  const target = plan.boards.flatMap(bd => bd.books.map(p => ({ ...p, bd }))).find(p => p.book.id === b.id);
  const targetLabel = target ? `R${target.bd.shelfNumber} · B${target.bd.board} · ${String(target.position).padStart(2, '0')}` : null;
  const facts = [
    ['Verlag', b.publisher],
    ['Erschienen', b.publishedYear],
    ['Seiten', b.pageCount && `${b.pageCount}${b.format ? ' · ' + FORMATS[b.format] : ''}`],
    ['Sprache', b.language && (LANGS[b.language] ?? b.language)],
    ['ISBN', b.isbn13 && { mono: b.isbn13 }],
    ['Neupreis', b.listPriceCents != null && euros(b.listPriceCents)],
    ['Privat verkauft', b.usedPriceCents != null && `ca. ${euros(b.usedPriceCents)}`],
    ['Ankauf (momox & Co.)', b.buybackPriceCents != null && (b.buybackPriceCents > 0 ? `ca. ${euros(b.buybackPriceCents)}` : 'kaum etwas')],
    ['Dicke', `${b.estimatedThicknessMm} mm${b.thicknessMm ? '' : ' (geschätzt)'}`],
  ].filter(([, v]) => v);
  const copiesRow = `<dt>Exemplare</dt><dd class="stepper"><button class="round sm" id="less" aria-label="Ein Exemplar weniger" ${b.copies <= 1 ? 'disabled' : ''}>${icons.minus}</button>
      <b>${b.copies}</b><button class="round sm" id="more" aria-label="Ein Exemplar mehr">${icons.plus}</button></dd>`;
  paint(`
    ${backBar('#/', `<a class="round" href="#/book/${b.id}/edit" aria-label="Bearbeiten">${icons.pen}</a>`)}
    <div class="stage"><label title="Cover ändern">${cover(b, { lazy: false })}<input id="coverfile" class="sr" type="file" accept="image/jpeg,image/png"></label></div>
    <h1 class="dtitle">${esc(b.title)}</h1>
    ${b.subtitle ? `<p class="dsub">${esc(b.subtitle)}</p>` : ''}
    <p class="dauthor">${esc(authors(b.authors))}</p>
    <div class="list">
      <div class="loc">
        <div><b class="${b.location ? 'mono' : ''}">${esc(b.location ?? 'Ohne Platz')}</b><small>${b.location ? 'steht hier laut letztem Regalfoto' : 'noch keinem Regal zugeordnet'}${targetLabel && targetLabel !== b.location ? ` · gehört nach <strong>${esc(targetLabel)}</strong>` : ''}</small></div>
        <a class="link" href="#/shelves">Einsortieren</a></div>
    </div>
    <dl class="facts">${facts.map(([k, v]) => `<dt>${k}</dt><dd>${v.mono ? `<span class="mono">${esc(v.mono)}</span>` : esc(v)}</dd>`).join('')}${copiesRow}</dl>
    ${b.description ? `<p class="blurb clamp" id="blurb">${esc(b.description)}</p>` : ''}
    <div class="actions"><a class="btn ghost" href="#/book/${b.id}/edit">${icons.pen} Bearbeiten</a><button class="btn danger" id="delete">Löschen</button></div>`);

  document.getElementById('blurb')?.addEventListener('click', e => e.currentTarget.classList.toggle('clamp'));
  const setCopies = async n => { await post(`/api/books/${b.id}/copies`, { copies: n }); invalidate(); route(); };
  document.getElementById('more').addEventListener('click', () => setCopies(b.copies + 1));
  document.getElementById('less').addEventListener('click', () => setCopies(b.copies - 1));
  document.getElementById('coverfile').addEventListener('change', async e => {
    const file = e.target.files?.[0];
    if (!file) return;
    const form = new FormData();
    form.append('image', await prepareImage(file, 1200), 'cover.jpg');
    await upload(`/api/books/${b.id}/cover`, form, 'PUT');
    invalidate();
    toast('Cover gespeichert');
    route();
  });
  document.getElementById('delete').addEventListener('click', async () => {
    if (!await ask(`„${b.title}“ löschen?`, 'Das Buch verschwindet aus der Bibliothek, samt Cover und Regalplatz.', 'Löschen', 'Abbrechen', true)) return;
    await del(`/api/books/${b.id}`);
    invalidate();
    toast('Gelöscht');
    go('/');
  });
}

async function viewEdit(id) {
  const b = id ? await get(`/api/books/${id}`) : { authors: [] };
  const field = (name, label, value, type = 'text', extra = '') =>
    `<div class="field"><label for="${name}">${label}</label><input id="${name}" name="${name}" type="${type}" value="${esc(value ?? '')}" ${extra}></div>`;
  paint(`
    ${backBar(id ? `#/book/${id}` : '#/add')}
    <h1 class="dtitle">${id ? 'Bearbeiten' : 'Von Hand anlegen'}</h1>
    <form class="form" id="f">
      ${field('title', 'Titel', b.title, 'text', 'required maxlength="500"')}
      ${field('subtitle', 'Untertitel', b.subtitle)}
      ${field('authors', 'Autor(en), mit ; getrennt', authors(b.authors).replaceAll(', ', '; '))}
      <div class="two">${field('isbn', 'ISBN', b.isbn13, 'text', 'inputmode="numeric"')}${field('publisher', 'Verlag', b.publisher)}</div>
      <div class="two">${field('publishedYear', 'Jahr', b.publishedYear, 'number', 'min="1400" max="2100"')}${field('pageCount', 'Seiten', b.pageCount, 'number', 'min="1"')}</div>
      <div class="two">
        <div class="field"><label for="format">Format</label><select id="format" name="format"><option value="">–</option>
          ${Object.entries(FORMATS).map(([k, v]) => `<option value="${k}" ${b.format === k ? 'selected' : ''}>${v}</option>`).join('')}</select></div>
        ${field('thicknessMm', 'Dicke in mm (leer = schätzen)', b.thicknessMm, 'number', 'min="1" max="300"')}</div>
      ${field('price', 'Neupreis in €', b.listPriceCents != null ? (b.listPriceCents / 100).toFixed(2).replace('.', ',') : '', 'text', 'inputmode="decimal"')}
      <div class="field"><label for="description">Klappentext</label><textarea id="description" name="description">${esc(b.description ?? '')}</textarea></div>
      <button class="btn block" type="submit">Speichern</button>
    </form>`);
  document.getElementById('f').addEventListener('submit', async e => {
    e.preventDefault();
    const f = new FormData(e.target);
    const num = k => f.get(k) ? Number(f.get(k)) : null;
    const price = String(f.get('price') ?? '').replace(',', '.').trim();
    const body = {
      title: f.get('title'), subtitle: f.get('subtitle'),
      authors: String(f.get('authors')).split(';').map(s => s.trim()).filter(Boolean),
      isbn: f.get('isbn') || null, publisher: f.get('publisher'),
      publishedYear: num('publishedYear'), pageCount: num('pageCount'), thicknessMm: num('thicknessMm'),
      format: f.get('format') || null,
      listPriceCents: price ? Math.round(Number(price) * 100) : null, description: f.get('description'),
    };
    const saved = id ? await put(`/api/books/${id}`, body) : await addOrCopy(another => post(`/api/books?anotherCopy=${another}`, body));
    if (!saved) return;
    invalidate();
    toast('Gespeichert');
    if (!id) await askAboutWishes();
    go(`/book/${saved.id}`);
  });
}

// ---------------------------------------------------------------- Hinzufügen & Nachschlagen

/** Uploads a photo as a scan; the review page reads it. */
async function submitScan(file, mode, shelfId = null, board = null) {
  const form = new FormData();
  form.append('image', await prepareImage(file), 'photo.jpg');
  form.append('mode', mode);
  if (shelfId) { form.append('shelfId', shelfId); form.append('board', board); }
  return upload('/api/scans', form);
}

async function photoScan(mode, shelfId, board, photo = null) {
  const file = photo ?? await capturePhoto(mode === 'SHELF'
    ? 'Gerade von vorn, ganze Bretter mit beiden Seitenwänden ins Bild'
    : 'Cover oder Buchrücken formatfüllend');
  if (!file) return;
  paint(pendingView('Foto wird hochgeladen …'));
  const scan = await submitScan(file, mode, shelfId, board);
  go(`/scan/${scan.id}`);
}

// Several photos from the gallery are reviewed one after the other; the rest waits here.
const QUEUE = 'shelved.scanQueue';
const scanPath = (id, wish) => `/scan/${id}${wish ? '?wunsch' : ''}`;
function nextQueuedScan(fallback) {
  const queue = JSON.parse(sessionStorage.getItem(QUEUE) ?? '{"ids":[]}');
  const next = queue.ids.shift();
  sessionStorage.setItem(QUEUE, JSON.stringify(queue));
  go(next ? scanPath(next, queue.wish) : fallback);
}

/**
 * Reads one photo: a barcode is that ISBN, without one the server reads the cover (and, outside the wish list,
 * decides between one book and a shelf). Resolves with { isbn } or { scan }.
 */
async function readPhoto(photo, purpose) {
  const form = new FormData();
  form.append('image', await prepareImage(photo, 2400), 'photo.jpg');
  try {
    return { isbn: (await upload('/api/barcode', form)).isbn };
  } catch (err) {
    if (err.status !== 404) throw err;
  }
  if (!state.me?.aiAvailable) throw new Error('Kein Barcode erkannt. Näher ran und scharf stellen.');
  return { scan: await submitScan(photo, purpose === 'wish' ? 'BOOK' : 'AUTO') };
}

/**
 * The one way in for a single book: a full-screen camera. Barcodes are read live, one after another, with a frame
 * drawn around them. The shutter takes a photo, "Fotos" imports pictures from the gallery: barcode, cover or shelf,
 * the app works out which. "Eintippen" switches to one field that takes an ISBN, a title or an author.
 */
async function viewFind(purpose, typing = new URLSearchParams(location.hash.split('?')[1] ?? '').has('eingabe')) {
  const forWish = purpose === 'wish';
  const base = forWish ? '/wishes/add' : '/add';
  const back = forWish ? '#/wishes' : '#/';
  if (typing || !cameraAvailable()) {
    paint(`
      ${backBar(back, cameraAvailable() ? `<a class="round" href="#${base}" aria-label="Zur Kamera" title="Zur Kamera">${icons.cam}</a>` : '')}
      <h1 class="dtitle">${forWish ? 'Buch merken' : 'Hab ich das schon?'}</h1>
      <form class="search" id="q">${icons.search}
        <input id="term" type="search" placeholder="ISBN, Titel oder Autor" aria-label="ISBN, Titel oder Autor" autocomplete="off" enterkeyhint="search" autofocus>
        <button class="cam" type="button" id="gallery" aria-label="Fotos hochladen" title="Fotos hochladen">${icons.image}</button>
      </form>
      <div id="out"></div>`);
    const out = document.getElementById('out');
    document.getElementById('q').addEventListener('submit', e => {
      e.preventDefault();
      const term = document.getElementById('term').value.trim();
      if (term) find(term, out, purpose);
    });
    document.getElementById('gallery').addEventListener('click', async () => importPhotos(await pickPhotos(), out, purpose));
    const given = new URLSearchParams(location.hash.split('?')[1] ?? '').get('q');
    if (given) { document.getElementById('term').value = given; find(given, out, purpose); }
    return;
  }
  document.body.classList.add('fullcam');
  const ai = state.me?.aiAvailable;
  const hint = !ai ? 'Barcode vor die Kamera halten' : forWish ? 'Barcode vor die Kamera halten oder das Cover fotografieren'
    : 'Barcode vor die Kamera halten, oder Cover oder Regal fotografieren';
  paint(`<div class="fcam">
      <video id="video" muted playsinline></video>
      <svg class="fcam-track" aria-hidden="true"><polygon/><line/></svg>
      <div class="fcam-top">
        <a class="round on-dark" href="${back}" aria-label="Schließen">${icons.x}</a>
        <p>${hint}</p>
        <span class="round-gap"></span>
      </div>
      <div class="fcam-sheet" id="out"></div>
      <div class="fcam-bar">
        <a class="fcam-side" href="#${base}?eingabe">${icons.keyboard}<span>Eintippen</span></a>
        <button class="shutter" id="shoot" aria-label="Foto aufnehmen"></button>
        <button class="fcam-side end" id="gallery">${icons.image}<span>Fotos</span></button>
      </div>
    </div>`);
  const out = document.getElementById('out');
  const video = document.getElementById('video');
  const tracker = barcodeTracker(video, app.querySelector('.fcam-track'));
  const cam = liveCamera(video, {
    onCodes: codes => tracker.update(codes),
    onIsbn: isbn => { navigator.vibrate?.(60); tracker.lock(); showIsbn(isbn, out, purpose); },
    onError: () => location.replace(`#${base}?eingabe`), // no camera or no permission: typing is all that's left
  });
  cleanup = () => { cam.stop(); tracker.stop(); document.body.classList.remove('fullcam'); };
  const leave = () => { cam.stop(); tracker.stop(); document.body.classList.remove('fullcam'); };
  document.getElementById('shoot').addEventListener('click', async e => {
    const btn = e.currentTarget;
    btn.disabled = true;
    out.innerHTML = pendingView('Foto wird gelesen …');
    try {
      const r = await readPhoto(await cam.shoot(), purpose);
      if (r.isbn) showIsbn(r.isbn, out, purpose);
      else { leave(); go(scanPath(r.scan.id, forWish)); }
    } catch (err) {
      out.innerHTML = `<p class="note warn">${esc(err.message)}</p>`;
    } finally { btn.disabled = false; }
  });
  document.getElementById('gallery').addEventListener('click', async () => {
    const files = await pickPhotos();
    if (files.length) importPhotos(files, out, purpose, leave);
  });
}

/**
 * Imports photos taken earlier: each one is read like a fresh one. Barcodes show up here right away, covers and
 * shelves go to the review page one after the other.
 */
async function importPhotos(files, out, purpose, leave = () => {}) {
  if (!files.length) return;
  const isbns = [], scans = [], problems = [];
  for (const [i, f] of files.entries()) {
    out.innerHTML = pendingView(files.length > 1 ? `Foto ${i + 1} von ${files.length} wird gelesen …` : 'Foto wird gelesen …');
    try {
      const r = await readPhoto(f, purpose);
      if (r.isbn) isbns.push(r.isbn); else scans.push(r.scan.id);
    } catch (err) {
      problems.push(`${f.name}: ${err.message}`);
    }
  }
  const wish = purpose === 'wish';
  if (!isbns.length && scans.length) {
    sessionStorage.setItem(QUEUE, JSON.stringify({ ids: scans.slice(1), wish }));
    leave();
    go(scanPath(scans[0], wish));
    return;
  }
  out.innerHTML = `${isbns.map((_, i) => `<div data-isbn-slot="${i}"></div>`).join('')}
    ${scans.length ? `<div class="actions"><button class="btn" id="review">${scans.length} ${scans.length === 1 ? 'weiteres Foto' : 'weitere Fotos'} prüfen</button></div>` : ''}
    ${problems.length ? `<p class="note warn">${problems.map(esc).join('<br>')}</p>` : ''}`;
  isbns.forEach((isbn, i) => showIsbn(isbn, out.querySelector(`[data-isbn-slot="${i}"]`), purpose));
  out.querySelector('#review')?.addEventListener('click', () => {
    sessionStorage.setItem(QUEUE, JSON.stringify({ ids: scans.slice(1), wish }));
    leave();
    go(scanPath(scans[0], wish));
  });
}

/** What was typed: an ISBN is looked up, anything else searched in the catalogues by title and author. */
async function find(term, out, purpose) {
  const digits = term.replace(/[^0-9xX]/g, '');
  if ((digits.length === 10 || digits.length === 13) && digits.length >= term.replace(/[\s-]/g, '').length) {
    return showIsbn(digits, out, purpose);
  }
  out.innerHTML = pendingView('Kataloge werden gefragt …');
  const hits = await get(`/api/catalog?title=${encodeURIComponent(term)}`);
  const manual = purpose === 'wish' ? '' : '<p class="note"><a class="link" href="#/new">Nicht dabei? Selbst eintragen</a></p>';
  if (!hits.length) { out.innerHTML = `<p class="note">Keine Treffer für „${esc(term)}“.</p>${manual}`; return; }
  out.innerHTML = `<div class="list">${hits.map((m, i) => `
    <button class="row" data-i="${i}" type="button">${cover({ title: m.title, authors: m.authors })}
      <div class="grow"><b>${esc(m.title)}</b><small>${esc(authors(m.authors))}</small><small>${esc([m.publisher, m.publishedYear].filter(Boolean).join(' · '))}</small></div>
      ${purpose === 'wish' ? icons.bookmark : icons.plus}</button>`).join('')}</div>${manual}`;
  applyVars(out);
  out.querySelectorAll('[data-i]').forEach(btn => btn.addEventListener('click', async () => {
    btn.disabled = true;
    const m = hits[Number(btn.dataset.i)];
    if (purpose === 'wish') {
      const w = await addWish(m.isbn13 ? { isbn: m.isbn13 } : { title: m.title, authors: m.authors }).finally(() => { btn.disabled = false; });
      if (w) go('/wishes');
      return;
    }
    const b = await addOrCopy(another => post(`/api/books/from-catalog?anotherCopy=${another}`, m)).finally(() => { btn.disabled = false; });
    if (!b) return;
    invalidate();
    toast(`„${b.title}“ hinzugefügt`);
    await askAboutWishes();
    go(`/book/${b.id}`);
  }));
}

async function showIsbn(isbn, out, purpose) {
  out.innerHTML = pendingView('Kataloge werden gefragt …');
  let r;
  try {
    r = await get(`/api/lookup?isbn=${encodeURIComponent(isbn)}`);
  } catch (e) {
    out.innerHTML = `<p class="note warn">${esc(e.message)}</p>`;
    return;
  }
  const wished = (await wishes()).find(w => w.isbn13 === r.isbn13);
  if (r.owned.length) {
    const b = r.owned[0];
    out.innerHTML = `<div class="result"><div class="list"><a class="row" href="#/book/${b.id}">${cover(b)}<div class="grow"><b>${esc(b.title)}</b><small>${esc(authors(b.authors))}</small></div><span class="tag owned">Hast du</span></a></div>
      <p class="note">${b.location ? `Steht bei <span class="mono">${esc(b.location)}</span>` : 'Noch ohne Regalplatz'}</p></div>`;
  } else if (r.metadata) {
    const m = r.metadata;
    const add = `<button class="btn" data-act="add">${icons.plus} In die Bibliothek</button>`;
    const wish = wished ? '' : `<button class="btn ${purpose === 'wish' ? '' : 'ghost'}" data-act="wish">${icons.bookmark} Merken</button>`;
    out.innerHTML = `<div class="result"><div class="list"><div class="row">${cover({ title: m.title, authors: m.authors })}<div class="grow"><b>${esc(m.title)}</b><small>${esc(authors(m.authors))}</small>
      <small>${esc([m.publisher, m.publishedYear, m.listPriceCents != null && euros(m.listPriceCents)].filter(Boolean).join(' · '))}</small></div>
      ${wished ? '<span class="tag new">Gemerkt</span>' : '<span class="tag plain">Neu</span>'}</div></div>
      <div class="actions">${purpose === 'wish' ? wish : add + wish}</div></div>`;
    out.querySelector('[data-act="add"]')?.addEventListener('click', async e => {
      const btn = e.currentTarget;
      btn.disabled = true;
      const b = await addOrCopy(another => post('/api/books/isbn', { isbn: r.isbn13, anotherCopy: another })).finally(() => { btn.disabled = false; });
      if (!b) return;
      invalidate();
      toast(b.copies > 1 ? `„${b.title}“: jetzt ${b.copies} Exemplare` : `„${b.title}“ hinzugefügt`);
      await askAboutWishes();
      showIsbn(isbn, out, purpose); // stays here: the next book can be scanned right away
    });
    out.querySelector('[data-act="wish"]')?.addEventListener('click', async e => {
      e.currentTarget.disabled = true;
      await addWish({ isbn: r.isbn13 });
      if (purpose === 'wish') go('/wishes'); else showIsbn(isbn, out, purpose);
    });
  } else {
    out.innerHTML = `<div class="result"><p>Zu <span class="mono">${esc(r.isbn13)}</span> kennen DNB, Google Books und Open Library nichts.</p>
      <div class="actions"><a class="btn ghost" href="${purpose === 'wish' ? '#/wishes/add?eingabe' : '#/new'}">${purpose === 'wish' ? 'Nach Titel suchen' : 'Von Hand anlegen'}</a></div></div>`;
  }
}

/** Puts a book on the wish list. Owned already: says so instead. */
async function addWish(body) {
  try {
    const w = await post('/api/wishes', body);
    state.wishes = null;
    toast(`„${w.title}“ gemerkt`);
    return w;
  } catch (e) {
    if (e.status !== 409) throw e;
    toast(`„${e.data.title}“ hast du schon`);
    return null;
  }
}

async function viewShelfScan() {
  const shelves = await get('/api/shelves');
  paint(`
    ${backBar('#/shelves')}
    <h1 class="dtitle">Regal fotografieren</h1>
    <p class="note">Gerade von vorn, so dass die Rücken lesbar sind. Ein Brett oder mehrere auf einmal. Wenn du sagst, welches das oberste Brett im Bild ist, merkt sich shelved auch, wo die Bücher gerade stehen.</p>
    <form class="form" id="f">
      <div class="two">
        <div class="field"><label for="shelf">Regal</label><select id="shelf"><option value="">– egal –</option>
          ${shelves.map(s => `<option value="${s.id}" data-boards="${s.boardWidthsMm.length}">${esc(s.name)}</option>`).join('')}</select></div>
        <div class="field"><label for="board">Oberstes Brett im Foto</label><select id="board" disabled><option value="">–</option></select></div>
      </div>
      <button class="btn block" type="submit">${icons.cam} Foto aufnehmen</button>
    </form>
    ${shelves.length ? '' : '<p class="note"><a class="link" href="#/shelves/new">Regale anlegen</a>, damit shelved dir auch sagen kann, wohin welches Buch gehört.</p>'}`);
  const shelfSel = document.getElementById('shelf');
  const boardSel = document.getElementById('board');
  shelfSel.addEventListener('change', () => {
    const n = Number(shelfSel.selectedOptions[0]?.dataset.boards ?? 0);
    boardSel.disabled = !n;
    boardSel.innerHTML = n ? Array.from({ length: n }, (_, i) => `<option value="${i + 1}">Boden ${i + 1}${i === 0 ? ' (ganz oben)' : ''}</option>`).join('') : '<option value="">–</option>';
  });
  document.getElementById('f').addEventListener('submit', e => {
    e.preventDefault();
    photoScan('SHELF', shelfSel.value || null, boardSel.value || null);
  });
}

const STATUS = { NEW: ['new', 'Neu'], OWNED: ['owned', 'Hast du'], UNSURE: ['unsure', 'Prüfen'], DUPLICATE: ['unsure', 'Doppelt im Foto'] };

async function viewScan(id) {
  paint(pendingView('Die Buchrücken werden gelesen … das dauert bei einem vollen Brett etwa eine halbe Minute.'));
  const scan = await poll(() => get(`/api/scans/${id}`), s => s.status !== 'PENDING');
  if (scan.status === 'FAILED') throw new Error(scan.error ?? 'Scan fehlgeschlagen');
  const wish = param('wunsch') !== null;
  const done = wish ? '/wishes' : (scan.shelfId ? '/shelves' : '/');
  if (scan.status === 'ACCEPTED') { toast('Dieser Scan ist schon übernommen'); nextQueuedScan(done); return; }
  const items = scan.items;
  if (!items.length) {
    toast('Auf dem Foto wurden keine Bücher erkannt', true);
    nextQueuedScan(wish ? '/wishes/add' : '/add');
    return;
  }
  const waiting = JSON.parse(sessionStorage.getItem(QUEUE) ?? '{"ids":[]}').ids.length;
  const count = s => items.filter(i => i.status === s).length;
  const rows = new Set(items.map(it => it.reading.row || 1)).size;
  const itemHtml = it => {
    const r = it.reading;
    const options = [it.candidate, ...(it.alternatives ?? [])].filter(Boolean);
    const [cls, label] = STATUS[it.status];
    return `<div class="row">
      ${it.status === 'OWNED' ? `<span class="pos" aria-label="hast du">${icons.check}</span>` : `<input class="check" type="checkbox" data-i="${it.index}" ${it.status === 'NEW' ? 'checked' : ''} aria-label="${wish ? 'merken' : 'übernehmen'}">`}
      ${cover(options[0] ?? { title: r.title ?? '?', authors: r.authors })}
      <div class="grow"><b>${esc(options[0]?.title ?? r.title ?? '?')}</b>
        <small>${esc(authors(options[0]?.authors?.length ? options[0].authors : r.authors))}</small>
        ${it.status === 'UNSURE' ? `<small>Gelesen: „${esc(r.spineText || r.title)}“</small>` : ''}
        ${it.status === 'DUPLICATE' ? '<small>Anhaken, wenn du es wirklich zweimal hast</small>' : ''}
        ${options.length > 1 && it.status !== 'OWNED' ? `<select class="pick" data-pick="${it.index}">${options.map(m => `<option value="${esc(m.isbn13 ?? '')}">${esc([m.publisher, m.publishedYear, m.isbn13].filter(Boolean).join(' · ') || m.title)}</option>`).join('')}</select>` : ''}
      </div><span class="tag ${cls}">${label}</span></div>`;
  };
  paint(`
    ${backBar(wish ? '#/wishes/add' : '#/add')}
    ${waiting ? `<small class="dauthor">Danach noch ${waiting} ${waiting === 1 ? 'Foto' : 'Fotos'}</small>` : ''}
    <h1 class="dtitle">${items.length} ${items.length === 1 ? 'Buch' : 'Bücher'} erkannt</h1>
    <div class="summary"><div><b>${count('NEW')}</b>neu</div><div><b>${count('OWNED')}</b>hast du schon</div><div><b>${count('UNSURE')}</b>prüfen</div></div>
    ${[...new Set(items.map(it => it.reading.row || 1))].sort((a, b) => a - b).map(row => `
      ${rows > 1 ? `<h3 class="sec">${scan.shelfId ? 'Boden ' + (scan.boardNumber + row - 1) : 'Brett ' + row + ' im Foto'}</h3>` : ''}
      <div class="list">${items.filter(it => (it.reading.row || 1) === row).map(itemHtml).join('')}</div>`).join('')}
    <div class="sticky-cta"><button class="btn block" id="accept"></button></div>`);
  const btn = document.getElementById('accept');
  const refresh = () => {
    const n = app.querySelectorAll('.check:checked').length;
    btn.textContent = wish ? (n ? `${n} ${n === 1 ? 'Buch' : 'Bücher'} merken` : 'Nichts merken')
      : n ? `${n} ${n === 1 ? 'Buch' : 'Bücher'} übernehmen` : (scan.shelfId ? 'Nur Platz speichern' : 'Nichts übernehmen');
  };
  refresh();
  app.querySelectorAll('.check').forEach(c => c.addEventListener('change', refresh));
  btn.addEventListener('click', async () => {
    btn.disabled = true;
    if (wish) {
      // the reading becomes wishes: the chosen edition by ISBN, otherwise title and author as read
      for (const c of app.querySelectorAll('.check:checked')) {
        const it = items.find(x => x.index === Number(c.dataset.i));
        const isbn = app.querySelector(`[data-pick="${c.dataset.i}"]`)?.value || it.candidate?.isbn13;
        await addWish(isbn ? { isbn } : { title: it.candidate?.title ?? it.reading.title, authors: it.candidate?.authors ?? it.reading.authors });
      }
      nextQueuedScan(done);
      return;
    }
    const decisions = [...app.querySelectorAll('.check')].map(c => ({
      index: Number(c.dataset.i), take: c.checked,
      anotherCopy: items.find(it => it.index === Number(c.dataset.i))?.status === 'DUPLICATE' && c.checked,
      isbn13: app.querySelector(`[data-pick="${c.dataset.i}"]`)?.value || null,
    }));
    const res = await post(`/api/scans/${id}/accept`, { items: decisions });
    invalidate();
    const est = (res.estimatedBoards ?? []).map(b => `Boden ${b.board} ≈ ${b.widthMm / 10} cm`).join(', ');
    toast(`${res.created.length} hinzugefügt${res.placed.length ? `, ${res.placed.length} Plätze gespeichert` : ''}${est ? `. Breite geschätzt: ${est}` : ''}`);
    await askAboutWishes();
    nextQueuedScan(done);
  });
}

// ---------------------------------------------------------------- Wunschliste

const wishMeta = w => authors(w.authors);

async function viewWishes() {
  const list = await wishes(true);
  const open = list.filter(w => !w.ownedBookId);
  const owned = list.filter(w => w.ownedBookId);
  const priced = open.filter(w => w.listPriceCents != null);
  const total = priced.reduce((sum, w) => sum + w.listPriceCents, 0);
  const sub = !list.length ? 'Noch leer' : `${open.length} ${open.length === 1 ? 'Buch' : 'Bücher'}${priced.length ? `, ${priced.length < open.length ? 'mindestens ' : ''}${euros(total)} Neupreis` : ''}`;
  const remove = w => `<button class="round sm" data-del="${w.id}" aria-label="„${esc(w.title)}“ von der Wunschliste nehmen" title="Von der Liste nehmen">${icons.x}</button>`;
  const rows = q => {
    const hit = w => !q || [w.title, ...(w.authors ?? []), w.isbn13 ?? ''].join(' ').toLowerCase().includes(q);
    const o = open.filter(hit), d = owned.filter(hit);
    // nothing on the list: the same words go straight to the catalogue
    const more = term => `<div class="actions"><a class="btn ghost" href="#/wishes/add?eingabe&q=${encodeURIComponent(term)}">${icons.search} „${esc(term)}“ im Katalog suchen</a></div>`;
    if (!list.length) return q ? more(q) : `<div class="empty"><h2>Merk dir Bücher, die dir fehlen</h2>
      <p>Titel oder Autor oben eingeben oder mit der Kamera Barcode oder Cover fotografieren. Kommt ein Buch später in die Bibliothek, fragt shelved, ob es von der Liste darf.</p></div>`;
    if (q && !o.length && !d.length) return `<p class="note">Auf der Liste steht nichts zu „${esc(q)}“.</p>${more(q)}`;
    return `${o.length ? `<div class="list">${o.map(w => `<div class="row">${cover(w)}
      <div class="grow"><b>${esc(w.title)}</b><small>${esc(wishMeta(w))}</small>${w.isbn13 ? `<small class="mono">${esc(w.isbn13)}</small>` : ''}</div>
      ${w.listPriceCents != null ? `<span class="num">${esc(euros(w.listPriceCents))}</span>` : ''}${remove(w)}</div>`).join('')}</div>`
      : q ? '' : '<p class="note">Alles, was du dir gemerkt hast, steht schon in der Bibliothek.</p>'}
    ${d.length ? `<h3 class="sec">Schon in der Bibliothek</h3>
      <div class="list">${d.map(w => `<div class="row">${cover(w)}
        <a class="grow" href="#/book/${w.ownedBookId}"><b>${esc(w.title)}</b><small>${esc(wishMeta(w))}</small></a>
        <span class="tag owned">Hast du</span>${remove(w)}</div>`).join('')}</div>` : ''}`;
  };
  paint(`
    ${viewHead('Wunschliste', sub)}
    <div class="search" role="search">${icons.search}
      <input id="wq" type="search" placeholder="Titel, Autor oder ISBN" autocomplete="off" enterkeyhint="search" aria-label="Wunschliste durchsuchen">
      <a class="cam" href="#/wishes/add" aria-label="Buch merken: scannen oder suchen" title="Buch merken">${icons.cam}</a></div>
    <div id="wishrows" class="gap-top">${rows('')}</div>
    ${open.length ? `<div class="actions"><button class="btn ghost" id="share">${icons.share} Als Einkaufsliste teilen</button></div>` : ''}`);
  const box = document.getElementById('wishrows');
  const bind = () => box.querySelectorAll('[data-del]').forEach(b => b.addEventListener('click', async () => {
    b.disabled = true;
    await del(`/api/wishes/${b.dataset.del}`).finally(() => { b.disabled = false; });
    state.wishes = null;
    toast('Von der Wunschliste genommen');
    route();
  }));
  // Enter with no match on the list searches the catalogue
  document.getElementById('wq').addEventListener('keydown', e => {
    const q = e.target.value.trim();
    if (e.key === 'Enter' && q && box.querySelector('a[href^="#/wishes/add?eingabe"]')) go(`/wishes/add?eingabe&q=${encodeURIComponent(q)}`);
  });
  document.getElementById('wq').addEventListener('input', e => { box.innerHTML = rows(e.target.value.trim().toLowerCase()); applyVars(box); bind(); });
  bind();
  warmCovers();
  document.getElementById('share')?.addEventListener('click', async () => {
    const text = 'Meine Wunschliste:\n' + open.map(w => `${w.title}${w.authors?.length ? ' (' + authors(w.authors) + ')' : ''}${w.isbn13 ? ', ISBN ' + w.isbn13 : ''}`).join('\n');
    if (navigator.share) await navigator.share({ title: 'Wunschliste', text }).catch(() => {});
    else { await navigator.clipboard.writeText(text); toast('In die Zwischenablage kopiert'); }
  });
}

// ---------------------------------------------------------------- Regale

let currentShelf = 0;
const shelfEstimated = new Map();

async function viewShelves() {
  const [shelves, plan] = await Promise.all([get('/api/shelves'), get('/api/placement')]);
  if (!shelves.length) {
    paint(`${viewHead('Regale')}
      <div class="empty"><h2>Leg deine Regale an</h2><p>Mit Anzahl und Breite der Böden rechnet shelved aus, welches Buch wohin gehört, sortiert nach Nachname.</p>
      <a class="btn" href="#/shelves/new">${icons.plus} Regal anlegen</a></div>`);
    return;
  }
  currentShelf = Math.min(currentShelf, shelves.length - 1);
  shelfEstimated.clear();
  shelves.forEach(s => s.boardsEstimated.forEach((e, i) => e && shelfEstimated.set(`${s.id}:${i + 1}`, true)));
  const shelf = shelves[currentShelf];
  const boards = plan.boards.filter(b => b.shelfId === shelf.id);
  paint(`
    ${viewHead('Einsortieren', 'Nach Nachname, dann Erscheinungsjahr', `<a class="round" href="#/add/shelf" aria-label="Regal fotografieren" title="Regal fotografieren">${icons.cam}</a><a class="round" href="#/shelves/new" aria-label="Regal anlegen" title="Regal anlegen">${icons.plus}</a>`)}
    <div class="summary"><div><b>${plan.toPlace}</b>neu einsortieren</div><div><b>${plan.toMove}</b>umstellen</div><div><b>${plan.overflow.length}</b>passen nicht</div></div>
    ${shelves.length < 2 ? '' : `<div class="seg" role="tablist">${shelves.map((s, i) => `<button role="tab" class="${i === currentShelf ? 'on' : ''}" data-s="${i}">${esc(s.name)}</button>`).join('')}</div>`}
    <div class="chips"><button class="chip on" data-f="all">Alle Bücher</button><button class="chip" data-f="changes">Nur Änderungen</button><a class="chip" href="#/shelves/${shelf.id}/edit">Regal bearbeiten</a></div>
    <div id="boards">${boards.map(bd => boardHtml(bd)).join('')}</div>
    ${plan.overflow.length ? `<h3 class="sec">Passt nicht mehr rein</h3><div class="list">${plan.overflow.map(b => `<a class="row" href="#/book/${b.id}">${cover(b)}<div class="grow"><b>${esc(b.title)}</b><small>${esc(authors(b.authors))}</small></div></a>`).join('')}</div>` : ''}`);
  app.querySelectorAll('[data-s]').forEach(b => b.addEventListener('click', () => { currentShelf = Number(b.dataset.s); route(); }));
  app.querySelectorAll('[data-f]').forEach(c => c.addEventListener('click', () => {
    app.querySelectorAll('[data-f]').forEach(x => x.classList.toggle('on', x === c));
    app.querySelectorAll('.row[data-status="OK"]').forEach(r => { r.hidden = c.dataset.f === 'changes'; });
  }));
}

function boardHtml(bd) {
  const pct = Math.round(100 * bd.usedMm / bd.widthMm);
  const first = bd.books[0]?.book;
  const last = bd.books.at(-1)?.book;
  const free = Math.round((bd.widthMm - bd.usedMm) / 10);
  const scale = 100 / Math.max(bd.widthMm, bd.usedMm);
  const est = shelfEstimated.get(`${bd.shelfId}:${bd.board}`);
  return `<section class="board">
    <div class="lbl"><b>Boden ${bd.board}</b><span>${bd.books.length} Bücher, ${pct} % voll${free > 0 ? `, ca. ${free} cm frei` : ''}${est ? ` (Breite ≈ ${bd.widthMm / 10} cm geschätzt)` : ''}</span></div>
    <div class="strip" aria-hidden="true">${bd.books.map(p => `<i class="${p.status === 'NEW' ? 'new' : p.status === 'MOVE' ? 'move' : ''}" title="${esc(p.book.title)}"
      data-vars='${esc(JSON.stringify({ '--w': (p.thicknessMm * scale).toFixed(2) + '%', '--h': (70 + (p.book.title.length % 5) * 6) + '%', '--c': colorFor(p.book.title) }))}'></i>`).join('')}</div>
    ${first ? `<p class="note">${esc(surname(first) || first.title)} bis ${esc(surname(last) || last.title)}</p>` : '<p class="note">leer</p>'}
    <div class="list">${bd.books.map(p => `<a class="row" href="#/book/${p.book.id}" data-status="${p.status}">
      <span class="pos">${String(p.position).padStart(2, '0')}</span>
      <div class="grow"><b>${esc(p.book.title)}</b><small>${esc(p.book.authorSort ?? authors(p.book.authors))}</small></div>
      ${p.status === 'NEW' ? '<span class="tag new">Neu</span>' : p.status === 'MOVE' ? `<span class="tag move">von <span class="mono">${esc(p.from)}</span></span>` : ''}</a>`).join('')}</div>
  </section>`;
}

const PRESETS = {
  billy80: { label: 'IKEA Billy 80 cm', boards: Array(6).fill(760) },
  billy40: { label: 'IKEA Billy 40 cm', boards: Array(6).fill(360) },
  unknown: { label: 'Breite unbekannt, aus dem Foto schätzen', boards: Array(5).fill(null) },
};

async function viewShelfForm(id) {
  const all = await get('/api/shelves');
  const existing = id ? all.find(s => s.id === id) : null;
  let widths = existing ? existing.boardWidthsMm.map((w, i) => existing.boardsEstimated[i] ? null : w) : PRESETS.unknown.boards;
  const estimates = existing ? existing.boardWidthsMm.map((w, i) => existing.boardsEstimated[i] ? w : null) : [];
  const boardFields = ws => ws.map((w, i) => `<div class="field"><label for="w${i}">Boden ${i + 1}${i === 0 ? ' (oben)' : ''}: Innenbreite in cm</label>
      <input id="w${i}" type="number" min="5" max="500" step="0.5" value="${w == null ? '' : w / 10}"
        placeholder="${estimates[i] ? '≈ ' + estimates[i] / 10 + ' (aus Foto geschätzt)' : 'unbekannt, aus Foto schätzen'}"></div>`).join('');
  paint(`
    ${backBar('#/shelves')}
    <h1 class="dtitle">${existing ? 'Regal bearbeiten' : 'Neues Regal'}</h1>
    <p class="note">Böden von oben nach unten. Leer lassen, wenn du die Breite nicht weißt: Nach dem ersten Regalfoto rechnet shelved sie aus den erkannten Büchern aus. Nachgemessen ist genauer.</p>
    <form class="form" id="f">
      <div class="field"><label for="name">Name</label><input id="name" required maxlength="100" value="${esc(existing?.name ?? 'Regal ' + (all.length + 1))}"></div>
      ${existing ? '' : `<div class="chips" id="presets">${Object.entries(PRESETS).map(([k, p]) => `<button type="button" class="chip ${k === 'unknown' ? 'on' : ''}" data-preset="${k}">${esc(p.label)}</button>`).join('')}</div>`}
      <div class="field"><label for="count">Anzahl Böden</label><input id="count" type="number" min="1" max="30" value="${widths.length}"></div>
      <div id="boards" class="form">${boardFields(widths)}</div>
      <button class="btn block" type="submit">Speichern</button>
      ${existing ? '<button class="btn danger block" type="button" id="delete">Regal löschen</button>' : ''}
    </form>`);
  const boardsEl = document.getElementById('boards');
  const current = () => [...boardsEl.querySelectorAll('input')].map(i => i.value ? Math.round(Number(i.value) * 10) : null);
  const render = ws => { widths = ws; boardsEl.innerHTML = boardFields(ws); document.getElementById('count').value = ws.length; };
  document.getElementById('count').addEventListener('change', e => {
    const n = Math.max(1, Math.min(30, Number(e.target.value)));
    const now = current();
    render(Array.from({ length: n }, (_, i) => i < now.length ? now[i] : now.at(-1) ?? null));
  });
  app.querySelectorAll('[data-preset]').forEach(b => b.addEventListener('click', () => {
    app.querySelectorAll('[data-preset]').forEach(x => x.classList.toggle('on', x === b));
    render([...PRESETS[b.dataset.preset].boards]);
    if (b.dataset.preset !== 'unknown') document.getElementById('name').value = PRESETS[b.dataset.preset].label.replace('IKEA ', '');
  }));
  document.getElementById('f').addEventListener('submit', async e => {
    e.preventDefault();
    const body = { name: document.getElementById('name').value, boardWidthsMm: current() };
    if (existing) await put(`/api/shelves/${existing.id}`, body); else await post('/api/shelves', body);
    toast('Regal gespeichert');
    go('/shelves');
  });
  document.getElementById('delete')?.addEventListener('click', async () => {
    if (!await ask(`„${existing.name}“ löschen?`, 'Die Bücher bleiben in der Bibliothek, nur ihr Platz wird vergessen.', 'Regal löschen', 'Abbrechen', true)) return;
    await del(`/api/shelves/${existing.id}`);
    currentShelf = 0;
    go('/shelves');
  });
}

// ---------------------------------------------------------------- Statistik

async function viewStats() {
  const s = await get('/api/stats');
  const [eur, cent] = euros(s.listValueCents).split(',');
  paint(`
    ${viewHead('Statistik')}
    <small class="dauthor">Wert deiner Bibliothek (Neupreis)</small>
    <div class="value">${esc(eur)}<small>,${esc(cent)}</small></div>
    <p class="dauthor">privat verkauft ca. ${esc(euros(s.usedValueCents))} · an Ankaufportale ca. ${esc(euros(s.buybackValueCents))}</p>
    <div class="kpis">
      <div><b>${s.books}</b><span>Bücher</span></div>
      <div><b>${s.shelves}</b><span>Regale</span></div>
      <div><b>${s.pages.toLocaleString('de-DE')}</b><span>Seiten</span></div>
      <div><b>${s.books - s.withPrice}</b><span>ohne bekannten Preis</span></div>
    </div>
    <h3 class="sec">Für die Versicherung</h3>
    <p class="note">Inventarliste mit Wiederbeschaffungswert (Neupreis), Standort, Covern und deinen Regalfotos als Nachweis für die Hausratversicherung.</p>
    <div class="actions"><a class="btn" href="/api/export/inventory.pdf" download>${icons.book} Inventarliste (PDF)</a><a class="btn ghost" href="/api/export/inventory.csv" download>Tabelle (CSV)</a></div>
    ${s.topAuthors.length ? `<h3 class="sec">Am häufigsten</h3><div class="list">${s.topAuthors.map(a => `<div class="row"><div class="grow"><b>${esc(a.author)}</b></div><span class="num">${a.books}</span></div>`).join('')}</div>` : ''}
    <p class="note">Neupreise kommen aus der Deutschen Nationalbibliothek. Gebrauchtwerte sind eine Faustregel nach dem Markt 2026: privat (Kleinanzeigen, Booklooker) je nach Alter 6–35 % vom Neupreis, Ankaufportale zahlen davon nochmal nur 20–40 %, für alte Taschenbücher meist nichts.</p>`);
}

// ---------------------------------------------------------------- Anmeldung & Konto

const param = name => new URLSearchParams(location.hash.split('?')[1] ?? '').get(name);
const ROLES = { OWNER: 'Eigentümer', ADMIN: 'Leitung', STAFF: 'Mitarbeit', VIEWER: 'Nur lesen' };
const KINDS = { PRIVATE: 'Privat/geteilt', PUBLIC: 'Öffentliche Bücherei', COMPANY: 'Firma/Fachbibliothek' };
const input = (name, label, type = 'text', extra = '', value = '') =>
  `<div class="field"><label for="${name}">${label}</label><input id="${name}" name="${name}" type="${type}" value="${esc(value)}" ${extra}></div>`;
const brand = '<div class="brand">shelved<i>.</i></div>';

/** Runs a form submit with a busy button and readable errors. */
function onSubmit(id, fn) {
  const f = document.getElementById(id);
  f.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = f.querySelector('button[type=submit]');
    btn.disabled = true;
    try { await fn(Object.fromEntries(new FormData(f)), f); } catch (err) { toast(err.message, true); } finally { btn.disabled = false; }
  });
}

async function afterLogin() {
  state.me = await get('/api/me');
  state.books = null;
  go(param('next') ?? '/');
}

async function viewLogin() {
  const opts = await get('/api/auth/options').catch(() => ({}));
  paint(`<div class="authbox">${brand}
    <h1 class="dtitle">Anmelden</h1>
    ${param('error') === 'sso' ? '<p class="note warn">Die Anmeldung über das Portal hat nicht geklappt.</p>' : ''}
    <form class="form" id="f">
      ${input('email', 'E-Mail', 'email', 'required autocomplete="username" autofocus')}
      ${input('password', 'Passwort', 'password', 'required autocomplete="current-password"')}
      <button class="btn block" type="submit">Anmelden</button>
    </form>
    ${opts.sso ? `<a class="btn ghost block sso" href="${esc(opts.ssoUrl)}">Mit tstieh-Portal anmelden</a>` : ''}
    <p class="linkrow"><a href="#/forgot">Passwort vergessen?</a><a href="#/register${param('next') ? '?next=' + encodeURIComponent(param('next')) : ''}">Neu hier? Konto anlegen</a></p></div>`);
  onSubmit('f', async v => { await post('/api/auth/login', v); await afterLogin(); });
}

async function viewRegister() {
  const token = param('invite');
  const inv = token ? await get(`/api/invitations/${encodeURIComponent(token)}`).catch(() => null) : null;
  paint(`<div class="authbox">${brand}
    <h1 class="dtitle">${inv ? `Mitmachen bei „${esc(inv.library)}“` : 'Konto anlegen'}</h1>
    <form class="form" id="f">
      ${input('name', 'Dein Name', 'text', 'required maxlength="120" autocomplete="name"')}
      ${input('email', 'E-Mail', 'email', `required autocomplete="email" ${inv ? 'readonly' : ''}`, inv?.email ?? '')}
      ${input('password', 'Passwort (mindestens 10 Zeichen)', 'password', 'required minlength="10" autocomplete="new-password"')}
      ${inv ? '' : `${input('libraryName', 'Name deiner Bibliothek', 'text', 'required maxlength="120" placeholder="z. B. Gemeindebücherei Nord"')}
      <div class="field"><label>Art</label><div class="chips" id="kinds">${Object.entries(KINDS).map(([k, v], i) =>
        `<button type="button" class="chip ${i === 0 ? 'on' : ''}" data-kind="${k}">${v}</button>`).join('')}</div></div>`}
      <button class="btn block" type="submit">Konto anlegen</button>
    </form>
    <p class="note">Mit dem Anlegen akzeptierst du, dass shelved deine Adresse für Anmeldung und Bestätigungsmails speichert. Du kannst dein Konto jederzeit löschen.</p>
    <p class="linkrow"><a href="#/login">Schon ein Konto? Anmelden</a></p></div>`);
  let kind = 'PRIVATE';
  document.getElementById('kinds')?.addEventListener('click', e => {
    const b = e.target.closest('[data-kind]');
    if (!b) return;
    kind = b.dataset.kind;
    document.querySelectorAll('#kinds .chip').forEach(c => c.classList.toggle('on', c === b));
  });
  onSubmit('f', async v => {
    await post('/api/auth/register', { ...v, kind, inviteToken: token });
    if (inv) {
      toast('Konto angelegt, du kannst dich jetzt anmelden');
      go('/login');
    } else {
      paint(`<div class="authbox">${brand}<h1 class="dtitle">Fast geschafft</h1>
        <p>Wir haben dir eine Mail an <b>${esc(v.email)}</b> geschickt. Klick auf den Link darin, dann kannst du dich anmelden.</p>
        <p class="linkrow"><a href="#/login">Zur Anmeldung</a></p></div>`);
    }
  });
}

async function viewVerify() {
  paint(`<div class="authbox">${brand}${pendingView('Adresse wird bestätigt …')}</div>`);
  try {
    await post('/api/auth/verify', { token: param('token') });
    paint(`<div class="authbox">${brand}<h1 class="dtitle">Bestätigt</h1><p>Deine Adresse ist bestätigt.</p>
      <a class="btn block" href="${state.me ? '#/account' : '#/login'}">${state.me ? 'Zum Konto' : 'Anmelden'}</a></div>`);
  } catch (e) {
    paint(`<div class="authbox">${brand}<h1 class="dtitle">Das hat nicht geklappt</h1><p>${esc(e.message)}</p>
      <a class="btn ghost block" href="#/login">Zur Anmeldung</a></div>`);
  }
}

async function viewForgot() {
  paint(`<div class="authbox">${brand}<h1 class="dtitle">Passwort vergessen</h1>
    <form class="form" id="f">${input('email', 'E-Mail', 'email', 'required autocomplete="email"')}
      <button class="btn block" type="submit">Link schicken</button></form>
    <p class="linkrow"><a href="#/login">Zurück zur Anmeldung</a></p></div>`);
  onSubmit('f', async v => {
    await post('/api/auth/forgot', v);
    paint(`<div class="authbox">${brand}<h1 class="dtitle">Schau in dein Postfach</h1>
      <p>Wenn es zu <b>${esc(v.email)}</b> ein Konto gibt, ist ein Link unterwegs. Er gilt eine Stunde.</p></div>`);
  });
}

async function viewReset() {
  paint(`<div class="authbox">${brand}<h1 class="dtitle">Neues Passwort</h1>
    <form class="form" id="f">${input('password', 'Neues Passwort (mindestens 10 Zeichen)', 'password', 'required minlength="10" autocomplete="new-password"')}
      <button class="btn block" type="submit">Speichern</button></form></div>`);
  onSubmit('f', async v => {
    await post('/api/auth/reset', { token: param('token'), password: v.password });
    toast('Passwort gespeichert');
    go('/login');
  });
}

async function viewInvite() {
  const token = param('token');
  let inv;
  try { inv = await get(`/api/invitations/${encodeURIComponent(token)}`); } catch (e) {
    paint(`<div class="authbox">${brand}<h1 class="dtitle">Einladung</h1><p>${esc(e.message)}</p></div>`);
    return;
  }
  if (!state.me) { try { state.me = await get('/api/me'); } catch { /* not logged in */ } }
  const here = encodeURIComponent(`/invite?token=${token}`);
  paint(`<div class="authbox">${brand}
    <small class="dauthor">Einladung${inv.invitedBy ? ' von ' + esc(inv.invitedBy) : ''}</small>
    <h1 class="dtitle">${esc(inv.library)}</h1>
    <p>Rolle: <b>${ROLES[inv.role]}</b></p>
    ${state.me ? '<button class="btn block" id="accept">Einladung annehmen</button>'
      : `<a class="btn block" href="#/register?invite=${encodeURIComponent(token)}">Konto anlegen und mitmachen</a>
         <a class="btn ghost block sso" href="#/login?next=${here}">Ich habe schon ein Konto</a>`}</div>`);
  document.getElementById('accept')?.addEventListener('click', async () => {
    await post(`/api/invitations/${encodeURIComponent(token)}/accept`, {});
    state.me = await get('/api/me');
    state.books = null;
    toast(`Willkommen in „${inv.library}“`);
    go('/');
  });
}

async function viewAccount() {
  const [acc, libs] = await Promise.all([get('/api/account'), get('/api/libraries')]);
  const lib = state.me.library;
  const manage = lib.role === 'OWNER' || lib.role === 'ADMIN';
  const [members, invites] = manage ? await Promise.all([get('/api/library/members'), get('/api/library/invitations')]) : [[], []];
  const roleSelect = (id, role) => `<select data-member="${id}" aria-label="Rolle">${Object.entries(ROLES)
    .filter(([k]) => k !== 'OWNER' || lib.role === 'OWNER').map(([k, v]) => `<option value="${k}" ${k === role ? 'selected' : ''}>${v}</option>`).join('')}</select>`;
  paint(`
    ${backBar('#/', themeButton())}
    <small class="dauthor">${esc(KINDS[lib.kind])} · ${ROLES[lib.role]}</small>
    <h1 class="dtitle">${esc(lib.name)}</h1>

    <h3 class="sec">Bibliotheken</h3>
    <div class="list">${libs.map(l => `<div class="row"><div class="grow"><b>${esc(l.name)}</b><small>${esc(KINDS[l.kind])} · ${ROLES[l.role]}</small></div>
      ${l.current ? '<span class="tag owned">Aktiv</span>' : `<button class="btn quiet" data-select="${l.id}">Wechseln</button>`}</div>`).join('')}</div>
    <form class="inline" id="newlib">${input('name', 'Neue Bibliothek anlegen', 'text', 'required maxlength="120" placeholder="Name"')}<button class="btn ghost" type="submit">Anlegen</button></form>
    ${manage ? `<form class="form" id="libform">${input('libname', 'Name dieser Bibliothek', 'text', 'required maxlength="120"', lib.name)}
      <div class="field"><label for="kind">Art</label><select id="kind" name="kind">${Object.entries(KINDS).map(([k, v]) => `<option value="${k}" ${k === lib.kind ? 'selected' : ''}>${v}</option>`).join('')}</select></div>
      <button class="btn ghost" type="submit">Speichern</button></form>` : ''}

    ${manage ? `<h3 class="sec">Team</h3>
    <div class="list">${members.map(m => `<div class="row"><div class="grow"><b>${esc(m.name ?? m.email ?? 'Ohne Namen')}</b><small>${esc(m.email ?? '')}</small></div>
      ${m.accountId === undefined ? '' : roleSelect(m.accountId, m.role)}
      <button class="round sm" data-remove="${m.accountId}" aria-label="Aus der Bibliothek entfernen">${icons.x}</button></div>`).join('')}
      ${invites.map(i => `<div class="row"><div class="grow"><b>${esc(i.email)}</b><small>eingeladen als ${ROLES[i.role]}</small></div>
      <button class="round sm" data-withdraw="${i.id}" aria-label="Einladung zurückziehen">${icons.x}</button></div>`).join('')}</div>
    <form class="inline" id="invite">${input('email', 'Jemanden einladen', 'email', 'required placeholder="E-Mail-Adresse"')}
      <select name="role" aria-label="Rolle">${Object.entries(ROLES).filter(([k]) => k !== 'OWNER' || lib.role === 'OWNER')
        .map(([k, v]) => `<option value="${k}" ${k === 'STAFF' ? 'selected' : ''}>${v}</option>`).join('')}</select>
      <button class="btn" type="submit">Einladen</button></form>` : `<p class="note">Du bist hier „${ROLES[lib.role]}“. Mitglieder verwaltet die Leitung.</p>`}

    <h3 class="sec">Mein Konto</h3>
    <form class="form" id="me">${input('name', 'Name', 'text', 'required maxlength="120"', acc.name ?? '')}<button class="btn ghost" type="submit">Name speichern</button></form>
    <form class="form" id="mail">${input('email', `E-Mail${acc.email ? ' (jetzt: ' + esc(acc.email) + ')' : ''}`, 'email', 'required')}
      ${acc.hasPassword ? input('password', 'Passwort zur Bestätigung', 'password', 'required autocomplete="current-password"') : ''}
      <button class="btn ghost" type="submit">Adresse ändern</button></form>
    <form class="form" id="pw">${acc.hasPassword ? input('current', 'Aktuelles Passwort', 'password', 'required autocomplete="current-password"') : ''}
      ${input('password', acc.hasPassword ? 'Neues Passwort' : 'Passwort festlegen (für die Anmeldung ohne Portal)', 'password', 'required minlength="10" autocomplete="new-password"')}
      <button class="btn ghost" type="submit">Passwort speichern</button></form>

    <h3 class="sec">Daten</h3>
    <p class="note">Eine Kopie aller Daten zu deinem Konto kommt als Datei per Mail an deine Adresse.</p>
    <div class="actions"><button class="btn ghost" id="export">Datenauskunft anfordern</button><button class="btn quiet" id="logout">Abmelden</button></div>

    <h3 class="sec">Konto löschen</h3>
    <p class="note">Löscht dein Konto. Bibliotheken, in denen du allein bist, werden mit allen Büchern, Regalen und Fotos gelöscht. Das lässt sich nicht rückgängig machen.</p>
    <form class="form" id="del">${acc.hasPassword ? input('password', 'Passwort zur Bestätigung', 'password', 'required autocomplete="current-password"') : ''}
      <button class="btn danger" type="submit">Konto endgültig löschen</button></form>`);

  const reload = async () => { state.me = await get('/api/me'); state.books = null; route(); };
  const onClick = async e => {
    const t = e.target.closest('button');
    if (!t) return;
    try {
      if (t.dataset.select) { await post(`/api/libraries/${t.dataset.select}/select`, {}); await reload(); }
      if (t.dataset.remove && await ask('Aus dieser Bibliothek entfernen?', 'Die Person verliert den Zugriff auf Bücher, Regale und Wunschliste.', 'Entfernen', 'Abbrechen', true)) { await del(`/api/library/members/${t.dataset.remove}`); await reload(); }
      if (t.dataset.withdraw) { await del(`/api/library/invitations/${t.dataset.withdraw}`); await reload(); }
      if (t.id === 'export') { await post('/api/account/export', {}); toast('Die Auskunft ist per Mail unterwegs'); }
      if (t.id === 'logout') { await post('/api/auth/logout', {}); forgetCovers(); state.me = null; state.books = null; go('/login'); }
    } catch (err) { toast(err.message, true); }
  };
  app.addEventListener('click', onClick);
  cleanup = () => app.removeEventListener('click', onClick);
  app.querySelectorAll('select[data-member]').forEach(s => s.addEventListener('change', async () => {
    try { await api(`/api/library/members/${s.dataset.member}`, { method: 'PATCH', json: { role: s.value } }); toast('Rolle geändert'); } catch (err) { toast(err.message, true); await reload(); }
  }));
  onSubmit('newlib', async v => { await post('/api/libraries', { name: v.name, kind: 'PRIVATE' }); await reload(); });
  if (manage) {
    onSubmit('libform', async v => { await api('/api/library', { method: 'PATCH', json: { name: v.libname, kind: v.kind } }); await reload(); });
    onSubmit('invite', async (v, f) => { await post('/api/library/invitations', v); toast(`Einladung an ${v.email} verschickt`); f.reset(); await reload(); });
  }
  onSubmit('me', async v => { await api('/api/account', { method: 'PATCH', json: v }); toast('Gespeichert'); state.me = await get('/api/me'); });
  onSubmit('mail', async v => { await post('/api/account/email', v); toast(`Bestätigungslink an ${v.email} geschickt`); });
  onSubmit('pw', async (v, f) => { await post('/api/account/password', v); toast('Passwort gespeichert'); f.reset(); });
  onSubmit('del', async v => {
    if (!await ask('Konto endgültig löschen?', 'Das lässt sich nicht rückgängig machen.', 'Konto löschen', 'Abbrechen', true)) return;
    await api('/api/account', { method: 'DELETE', json: v });
    forgetCovers();
    state.me = null;
    go('/login');
    toast('Dein Konto ist gelöscht');
  });
}

// ---------------------------------------------------------------- Start

document.addEventListener('click', e => { if (e.target.closest('[data-theme-toggle]')) toggleTheme(); });
// Covers fade in once loaded; until then their box is the skeleton.
document.addEventListener('load', e => { if (e.target.matches?.('.cover img')) e.target.closest('.cover').classList.add('loaded'); }, true);
document.addEventListener('error', e => { if (e.target.matches?.('.cover img')) e.target.closest('.cover').classList.add('loaded', 'broken'); }, true);
navigator.serviceWorker?.register('/sw.js').catch(() => { /* covers just come from the network */ });
window.addEventListener('hashchange', route);
window.addEventListener('unhandledrejection', e => toast(e.reason?.message ?? 'Fehler', true));
route();

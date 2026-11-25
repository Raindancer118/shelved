import { api, get, post, put, del, upload, poll, PUBLIC } from './api.js';
import { esc, icons, cover, applyVars, surname, euros, authors, toast, pendingView, colorFor, themeButton, toggleTheme, dialog } from './ui.js';
import { liveBarcodeSupported, scanBarcode, prepareImage, capturePhoto } from './camera.js';

const app = document.getElementById('app');
const state = { me: null, books: null, wishes: null, sort: 'author', query: '' };
let cleanup = null; // stops a running camera when the view changes

// ---------------------------------------------------------------- Router

const routes = [
  [/^\/$/, viewLibrary, 'library'],
  [/^\/book\/([\w-]+)$/, viewBook, 'library'],
  [/^\/book\/([\w-]+)\/edit$/, viewEdit, 'library'],
  [/^\/new$/, viewEdit, 'library'],
  [/^\/add$/, viewAdd, 'library'],
  [/^\/add\/barcode$/, () => viewBarcode('add'), 'library'],
  [/^\/lookup$/, () => viewBarcode('lookup'), 'library'],
  [/^\/add\/manual$/, viewManual, 'library'],
  [/^\/add\/shelf$/, viewShelfScan, 'library'],
  [/^\/scan\/([\w-]+)$/, viewScan, 'library'],
  [/^\/series$/, viewSeriesList, 'series'],
  [/^\/series\/(.+)$/, viewSeries, 'series'],
  [/^\/shelves$/, viewShelves, 'shelves'],
  [/^\/shelves\/new$/, viewShelfForm, 'shelves'],
  [/^\/shelves\/([\w-]+)\/edit$/, viewShelfForm, 'shelves'],
  [/^\/stats$/, viewStats, 'stats'],
  [/^\/wishes$/, viewWishes, 'wishes'],
  [/^\/wishes\/add$/, viewWishAdd, 'wishes'],
  [/^\/wishes\/barcode$/, () => viewBarcode('wish'), 'wishes'],
  [/^\/wishes\/search$/, () => viewManual('wish'), 'wishes'],
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
const WIDE = [/^\/$/, /^\/wishes$/, /^\/series/, /^\/shelves$/, /^\/stats$/];

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
function paint(html) { app.innerHTML = html; applyVars(app); }
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
    const hay = [b.title, ...(b.authors ?? []), b.seriesName ?? ''].join(' ').toLowerCase();
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
    ${viewHead('Bibliothek', `${esc(state.me?.library?.name ?? '')} · ${list.length} Bücher in ${shelves.length} ${shelves.length === 1 ? 'Regal' : 'Regalen'}`, avatar)}
    <div class="search" role="search">${icons.search}
      <input id="q" type="search" placeholder="Titel, Autor oder ISBN" value="${esc(state.query)}" autocomplete="off" aria-label="Bibliothek durchsuchen">
      <a class="cam" href="#/lookup" aria-label="Mit der Kamera nachschauen">${icons.cam}</a></div>
    <div class="chips">${Object.entries(SORTS).map(([k, v]) => `<button class="chip ${state.sort === k ? 'on' : ''}" data-sort="${k}">${v}</button>`).join('')}</div>
    <div id="results">${gridHtml(filtered(sorted(list)))}</div>
    ${state.sort === 'author' && !state.query ? `<div class="index" aria-label="Springe zu Buchstabe">${letters.map(l => `<button data-letter="${esc(l)}">${esc(l)}</button>`).join('')}</div>` : ''}
    <a class="btn fab" href="#/add">${icons.plus} Hinzufügen</a>`);

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
      ${b.seriesName ? `<a class="row" href="#/series/${encodeURIComponent(b.seriesKey)}"><div class="grow"><b>${esc(b.seriesName)}</b><small>Reihe${b.seriesVolume ? ', Band ' + b.seriesVolume : ''}</small></div>${icons.next}</a>` : ''}
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
      <div class="two">${field('seriesName', 'Reihe', b.seriesName)}${field('seriesVolume', 'Band', b.seriesVolume, 'number', 'min="0"')}</div>
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
      format: f.get('format') || null, seriesName: f.get('seriesName') ?? '', seriesVolume: num('seriesVolume'),
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

async function viewAdd() {
  const ai = state.me?.aiAvailable;
  paint(`
    ${backBar('#/')}
    <h1 class="dtitle">Hinzufügen</h1>
    <div class="list modes">
      <a class="row mode" href="#/add/shelf"><span class="ic">${icons.shelf}</span><span class="grow"><b>Regal fotografieren</b><small>${ai ? 'Ein Foto pro Regalbrett, die Buchrücken werden gelesen' : 'Nicht eingerichtet (kein API-Key)'}</small></span></a>
      <a class="row mode" href="#/add/barcode"><span class="ic">${icons.barcode}</span><span class="grow"><b>Barcode scannen</b><small>ISBN auf der Rückseite, Daten aus DNB und Google Books</small></span></a>
      <button class="row mode" id="bookphoto"><span class="ic">${icons.book}</span><span class="grow"><b>Buch fotografieren</b><small>Cover oder Rücken eines einzelnen Buchs</small></span></button>
      <a class="row mode" href="#/add/manual"><span class="ic">${icons.pen}</span><span class="grow"><b>Von Hand</b><small>Im Katalog suchen oder selbst eintragen</small></span></a>
    </div>`);
  document.getElementById('bookphoto').addEventListener('click', () => photoScan('BOOK'));
}

async function photoScan(mode, shelfId, board) {
  const file = await capturePhoto(mode === 'SHELF'
    ? 'Gerade von vorn, ganze Bretter mit beiden Seitenwänden ins Bild'
    : 'Cover oder Buchrücken formatfüllend');
  if (!file) return;
  paint(pendingView('Foto wird hochgeladen …'));
  const form = new FormData();
  form.append('image', await prepareImage(file), 'photo.jpg');
  form.append('mode', mode);
  if (shelfId) { form.append('shelfId', shelfId); form.append('board', board); }
  const scan = await upload('/api/scans', form);
  go(`/scan/${scan.id}`);
}

async function viewBarcode(purpose) {
  const live = await liveBarcodeSupported();
  paint(`
    ${backBar({ add: '#/add', wish: '#/wishes/add' }[purpose] ?? '#/')}
    <h1 class="dtitle">${{ add: 'Barcode scannen', wish: 'Buch merken' }[purpose] ?? 'Hab ich das schon?'}</h1>
    ${live ? '<div class="viewfinder"><video id="video" muted playsinline></video></div>' : ''}
    <div class="actions">
      <button class="btn ${live ? 'ghost' : ''}" id="photo">${icons.cam} ${live ? 'Lieber ein Foto' : 'Barcode fotografieren'}</button>
      ${purpose === 'lookup' && state.me?.aiAvailable ? `<button class="btn ghost" id="cover">${icons.book} Cover fotografieren</button>` : ''}
    </div>
    <form class="search" id="manual">
      <input id="isbn" inputmode="numeric" placeholder="ISBN eintippen" aria-label="ISBN" autocomplete="off">
      <button class="cam" type="submit" aria-label="Suchen">${icons.search}</button></form>
    <div id="out"></div>`);

  const out = document.getElementById('out');
  const handle = isbn => showIsbn(isbn, out, purpose);
  if (live) {
    cleanup = scanBarcode(document.getElementById('video'), (isbn, err) => {
      if (err) { toast('Kamera nicht verfügbar: ' + err.message, true); return; }
      navigator.vibrate?.(60);
      handle(isbn);
    });
  }
  document.getElementById('photo').addEventListener('click', async () => {
    cleanup?.();
    const file = await capturePhoto('Barcode auf der Rückseite, nah und scharf');
    if (!file) return;
    out.innerHTML = pendingView('Barcode wird gelesen …');
    const form = new FormData();
    form.append('image', await prepareImage(file, 2400), 'barcode.jpg');
    try {
      const { isbn } = await upload('/api/barcode', form);
      handle(isbn);
    } catch (e) {
      out.innerHTML = `<p class="note warn">${esc(e.status === 404 ? 'Kein Barcode erkannt. Näher ran und scharf stellen, oder ISBN eintippen.' : e.message)}</p>`;
    }
  });
  document.getElementById('cover')?.addEventListener('click', () => photoScan('BOOK'));
  document.getElementById('manual').addEventListener('submit', e => {
    e.preventDefault();
    handle(document.getElementById('isbn').value);
  });
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
    const add = `<button class="btn" id="addit">${icons.plus} In die Bibliothek</button>`;
    const wish = wished ? '' : `<button class="btn ${purpose === 'wish' ? '' : 'ghost'}" id="wishit">${icons.bookmark} Merken</button>`;
    out.innerHTML = `<div class="result"><div class="list"><div class="row">${cover({ title: m.title, authors: m.authors })}<div class="grow"><b>${esc(m.title)}</b><small>${esc(authors(m.authors))}</small>
      <small>${esc([m.publisher, m.publishedYear, m.listPriceCents != null && euros(m.listPriceCents)].filter(Boolean).join(' · '))}</small></div>
      ${wished ? '<span class="tag new">Gemerkt</span>' : '<span class="tag plain">Neu</span>'}</div></div>
      <div class="actions">${purpose === 'wish' ? wish : add + wish}</div></div>`;
    document.getElementById('addit')?.addEventListener('click', async e => {
      const btn = e.currentTarget;
      btn.disabled = true;
      const b = await addOrCopy(another => post('/api/books/isbn', { isbn: r.isbn13, anotherCopy: another })).finally(() => { btn.disabled = false; });
      if (!b) return;
      invalidate();
      toast(b.copies > 1 ? `„${b.title}“: jetzt ${b.copies} Exemplare` : `„${b.title}“ hinzugefügt`);
      await askAboutWishes();
      if (purpose === 'add') { out.innerHTML = ''; document.getElementById('isbn').value = ''; } else go(`/book/${b.id}`);
    });
    document.getElementById('wishit')?.addEventListener('click', async e => {
      e.currentTarget.disabled = true;
      await addWish({ isbn: r.isbn13 });
      if (purpose === 'wish') go('/wishes'); else showIsbn(isbn, out, purpose);
    });
  } else {
    out.innerHTML = `<div class="result"><p>Zu <span class="mono">${esc(r.isbn13)}</span> kennen DNB, Google Books und Open Library nichts.</p>
      <div class="actions"><a class="btn ghost" href="${purpose === 'wish' ? '#/wishes/search' : '#/new'}">${purpose === 'wish' ? 'Im Katalog suchen' : 'Von Hand anlegen'}</a></div></div>`;
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

async function viewManual(purpose = 'add') {
  const forWish = purpose === 'wish';
  paint(`
    ${backBar(forWish ? '#/wishes/add' : '#/add')}
    <h1 class="dtitle">${forWish ? 'Buch merken' : 'Im Katalog suchen'}</h1>
    <form class="form" id="f">
      <div class="field"><label for="t">Titel</label><input id="t" name="t" required></div>
      <div class="field"><label for="a">Autor (optional)</label><input id="a" name="a"></div>
      <button class="btn block" type="submit">${icons.search} Suchen</button>
    </form>
    <div id="out"></div>
    ${forWish ? '' : '<p class="note"><a class="link" href="#/new">Nicht dabei? Selbst eintragen</a></p>'}`);
  document.getElementById('f').addEventListener('submit', async e => {
    e.preventDefault();
    const out = document.getElementById('out');
    out.innerHTML = pendingView('Kataloge werden gefragt …');
    const hits = await get(`/api/catalog?title=${encodeURIComponent(e.target.t.value)}&author=${encodeURIComponent(e.target.a.value)}`);
    if (!hits.length) { out.innerHTML = '<p class="note">Keine Treffer.</p>'; return; }
    out.innerHTML = `<div class="list">${hits.map((m, i) => `
      <button class="row" data-i="${i}" type="button">${cover({ title: m.title, authors: m.authors })}
        <div class="grow"><b>${esc(m.title)}</b><small>${esc(authors(m.authors))}</small><small>${esc([m.publisher, m.publishedYear, m.isbn13].filter(Boolean).join(' · '))}</small></div>
        <span class="tag plain">${esc(m.source)}</span></button>`).join('')}</div>`;
    applyVars(out);
    out.querySelectorAll('[data-i]').forEach(btn => btn.addEventListener('click', async () => {
      btn.disabled = true;
      const m = hits[Number(btn.dataset.i)];
      if (forWish) {
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
  });
}

async function viewShelfScan() {
  const shelves = await get('/api/shelves');
  paint(`
    ${backBar('#/add')}
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
  if (scan.status === 'ACCEPTED') { toast('Dieser Scan ist schon übernommen'); go('/'); return; }
  const items = scan.items;
  if (!items.length) throw new Error('Auf dem Foto wurden keine Bücher erkannt.');
  const count = s => items.filter(i => i.status === s).length;
  const rows = new Set(items.map(it => it.reading.row || 1)).size;
  const itemHtml = it => {
    const r = it.reading;
    const options = [it.candidate, ...(it.alternatives ?? [])].filter(Boolean);
    const [cls, label] = STATUS[it.status];
    return `<div class="row">
      ${it.status === 'OWNED' ? `<span class="pos" aria-label="hast du">${icons.check}</span>` : `<input class="check" type="checkbox" data-i="${it.index}" ${it.status === 'NEW' ? 'checked' : ''} aria-label="übernehmen">`}
      <div class="grow"><b>${esc(options[0]?.title ?? r.title ?? '?')}</b>
        <small>${esc(authors(options[0]?.authors?.length ? options[0].authors : r.authors))}${r.series ? ' · ' + esc(r.series) + (r.volume ? ' ' + r.volume : '') : ''}</small>
        ${it.status === 'UNSURE' ? `<small>Gelesen: „${esc(r.spineText || r.title)}“</small>` : ''}
        ${it.status === 'DUPLICATE' ? '<small>Anhaken, wenn du es wirklich zweimal hast</small>' : ''}
        ${options.length > 1 && it.status !== 'OWNED' ? `<select class="pick" data-pick="${it.index}">${options.map(m => `<option value="${esc(m.isbn13 ?? '')}">${esc([m.publisher, m.publishedYear, m.isbn13].filter(Boolean).join(' · ') || m.title)}</option>`).join('')}</select>` : ''}
      </div><span class="tag ${cls}">${label}</span></div>`;
  };
  paint(`
    ${backBar('#/add')}
    <h1 class="dtitle">${items.length} ${items.length === 1 ? 'Buch' : 'Bücher'} erkannt</h1>
    <div class="summary"><div><b>${count('NEW')}</b>neu</div><div><b>${count('OWNED')}</b>hast du schon</div><div><b>${count('UNSURE')}</b>prüfen</div></div>
    ${[...new Set(items.map(it => it.reading.row || 1))].sort((a, b) => a - b).map(row => `
      ${rows > 1 ? `<h3 class="sec">${scan.shelfId ? 'Boden ' + (scan.boardNumber + row - 1) : 'Brett ' + row + ' im Foto'}</h3>` : ''}
      <div class="list">${items.filter(it => (it.reading.row || 1) === row).map(itemHtml).join('')}</div>`).join('')}
    <div class="sticky-cta"><button class="btn block" id="accept"></button></div>`);
  const btn = document.getElementById('accept');
  const refresh = () => {
    const n = app.querySelectorAll('.check:checked').length;
    btn.textContent = n ? `${n} ${n === 1 ? 'Buch' : 'Bücher'} übernehmen` : (scan.shelfId ? 'Nur Platz speichern' : 'Nichts übernehmen');
  };
  refresh();
  app.querySelectorAll('.check').forEach(c => c.addEventListener('change', refresh));
  btn.addEventListener('click', async () => {
    btn.disabled = true;
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
    go(scan.shelfId ? '/shelves' : '/');
  });
}

// ---------------------------------------------------------------- Wunschliste

const wishMeta = w => [authors(w.authors), w.seriesName && `${w.seriesName}${w.seriesVolume ? ' ' + w.seriesVolume : ''}`].filter(Boolean).join(' · ');

async function viewWishes() {
  const list = await wishes(true);
  if (!list.length) {
    paint(`${viewHead('Wunschliste', 'Noch leer')}
      <div class="empty"><h2>Merk dir Bücher, die dir fehlen</h2>
        <p>Im Buchladen den Barcode scannen, im Katalog suchen oder in einer Reihe auf einen fehlenden Band tippen. Kommt ein Buch später in die Bibliothek, fragt shelved, ob es von der Liste darf.</p>
        <a class="btn" href="#/wishes/add">${icons.bookmark} Buch merken</a></div>`);
    return;
  }
  const open = list.filter(w => !w.ownedBookId);
  const owned = list.filter(w => w.ownedBookId);
  const priced = open.filter(w => w.listPriceCents != null);
  const total = priced.reduce((sum, w) => sum + w.listPriceCents, 0);
  const sub = `${open.length} ${open.length === 1 ? 'Buch' : 'Bücher'}${priced.length ? `, ${priced.length < open.length ? 'mindestens ' : ''}${euros(total)} Neupreis` : ''}`;
  const remove = w => `<button class="round sm" data-del="${w.id}" aria-label="„${esc(w.title)}“ von der Wunschliste nehmen" title="Von der Liste nehmen">${icons.x}</button>`;
  paint(`
    ${viewHead('Wunschliste', sub)}
    ${open.length ? `<div class="list">${open.map(w => `<div class="row">${cover(w)}
      <div class="grow"><b>${esc(w.title)}</b><small>${esc(wishMeta(w))}</small>${w.isbn13 ? `<small class="mono">${esc(w.isbn13)}</small>` : ''}</div>
      ${w.listPriceCents != null ? `<span class="num">${esc(euros(w.listPriceCents))}</span>` : ''}${remove(w)}</div>`).join('')}</div>`
      : '<p class="note">Alles, was du dir gemerkt hast, steht schon in der Bibliothek.</p>'}
    ${owned.length ? `<h3 class="sec">Schon in der Bibliothek</h3>
      <div class="list">${owned.map(w => `<div class="row">${cover(w)}
        <a class="grow" href="#/book/${w.ownedBookId}"><b>${esc(w.title)}</b><small>${esc(wishMeta(w))}</small></a>
        <span class="tag owned">Hast du</span>${remove(w)}</div>`).join('')}</div>` : ''}
    ${open.length ? `<div class="actions"><button class="btn ghost" id="share">${icons.share} Als Einkaufsliste teilen</button></div>` : ''}
    <a class="btn fab" href="#/wishes/add">${icons.bookmark} Merken</a>`);
  app.querySelectorAll('[data-del]').forEach(b => b.addEventListener('click', async () => {
    b.disabled = true;
    await del(`/api/wishes/${b.dataset.del}`).finally(() => { b.disabled = false; });
    state.wishes = null;
    toast('Von der Wunschliste genommen');
    route();
  }));
  document.getElementById('share')?.addEventListener('click', async () => {
    const text = 'Meine Wunschliste:\n' + open.map(w => `${w.title}${w.authors?.length ? ' (' + authors(w.authors) + ')' : ''}${w.isbn13 ? ', ISBN ' + w.isbn13 : ''}`).join('\n');
    if (navigator.share) await navigator.share({ title: 'Wunschliste', text }).catch(() => {});
    else { await navigator.clipboard.writeText(text); toast('In die Zwischenablage kopiert'); }
  });
}

async function viewWishAdd() {
  paint(`
    ${backBar('#/wishes')}
    <h1 class="dtitle">Buch merken</h1>
    <div class="list modes">
      <a class="row mode" href="#/wishes/barcode"><span class="ic">${icons.barcode}</span><span class="grow"><b>Barcode scannen</b><small>Im Buchladen: zeigt auch, ob du es schon hast</small></span></a>
      <a class="row mode" href="#/wishes/search"><span class="ic">${icons.search}</span><span class="grow"><b>Im Katalog suchen</b><small>Nach Titel und Autor</small></span></a>
      <a class="row mode" href="#/series"><span class="ic">${icons.bookmark}</span><span class="grow"><b>Aus einer Reihe</b><small>Auf einen fehlenden Band tippen</small></span></a>
    </div>`);
}

// ---------------------------------------------------------------- Reihen

async function viewSeriesList() {
  const list = await get('/api/series');
  if (!list.length) {
    paint(`${viewHead('Reihen')}<div class="empty"><h2>Noch keine Reihen</h2><p>Sobald Bücher einer Reihe in deiner Bibliothek sind, siehst du hier, welche Bände fehlen.</p></div>`);
    return;
  }
  const missing = list.reduce((s, r) => s + r.missingCount, 0);
  paint(`
    ${viewHead('Reihen', `${list.length} Reihen, ${missing} Bände fehlen`, `<button class="round" id="recheck" aria-label="Reihen neu prüfen" title="Reihen neu prüfen">${icons.refresh}</button>`)}
    <div class="list">${list.map(r => {
      const total = r.knownTotal ?? (r.ownedCount + r.missingCount);
      const pct = total ? Math.round(100 * r.ownedCount / total) : 100;
      const state = r.missingCount ? '' : r.knownTotal ? 'done' : 'unknown';
      return `<a class="series-row" href="#/series/${encodeURIComponent(r.key)}">
        <div class="top"><b>${esc(r.name)}</b><em class="${r.missingCount ? 'miss' : ''}">${r.ownedCount}${r.knownTotal ? ' / ' + r.knownTotal : ''}${r.missingCount ? ' · ' + r.missingCount + ' fehlen' : r.knownTotal ? ' komplett' : ' · Bandliste unbekannt'}</em></div>
        <small>${esc(r.author ?? '')}${r.catalogStatus === 'PENDING' ? ' · Bandliste wird geladen' : ''}</small>
        <div class="track ${state}"><i data-vars='${esc(JSON.stringify({ '--p': pct + '%' }))}'></i></div></a>`;
    }).join('')}</div>`);
  document.getElementById('recheck').addEventListener('click', async () => {
    const r = await post('/api/series/recheck');
    toast(`${r.books} Bücher werden neu geprüft (Bibliotheken, Wikidata, Open Library, Hardcover)`);
    setTimeout(() => location.hash === '#/series' && route(), 6000);
  });
  if (list.some(r => r.catalogStatus === 'PENDING')) {
    const t = setTimeout(() => location.hash === '#/series' && route(), 4000);
    cleanup = () => clearTimeout(t);
  }
}

async function viewSeries(key) {
  const [s, wishList] = await Promise.all([get(`/api/series/${encodeURIComponent(key)}`), wishes()]);
  const isWished = v => wishList.some(w => (w.seriesName === s.name && w.seriesVolume === v.number)
    || (v.title && w.title.toLowerCase() === v.title.toLowerCase()));
  const total = s.knownTotal;
  const pct = total ? Math.round(100 * s.ownedCount / total) : 0;
  const source = { CATALOG: 'Bandliste aus Bibliothekskatalogen', WIKIDATA: 'Bandliste aus Wikidata', HARDCOVER: 'Bandliste von Hardcover', CLAUDE: 'Bandliste per KI-Recherche' }[s.catalogSource];
  const statusNote = s.catalogStatus === 'PENDING' ? '<p class="note">Bandliste wird geladen …</p>'
    : s.catalogStatus === 'FAILED' ? `<p class="note warn">${esc(s.catalogNote ?? 'Keine Bandliste gefunden')}. Lücken zwischen deinen Bänden siehst du trotzdem.</p>`
    : `<p class="note">${esc(source ?? '')}${s.catalogNote ? ' · ' + esc(s.catalogNote) : ''}</p>`;
  const missing = s.volumes.filter(v => !v.book);
  paint(`
    ${backBar('#/series', `<button class="round" id="refresh" aria-label="Bandliste neu prüfen" title="Bandliste neu prüfen">${icons.refresh}</button>`)}
    <small class="dauthor">${esc(s.author ?? '')}</small>
    <h1 class="dtitle">${esc(s.name)}</h1>
    <div class="big"><b>${s.ownedCount}</b><span>${total ? `von ${total} Bänden` : 'Bände'}${s.missingCount ? ` · ${s.missingCount} fehlen` : ''}</span></div>
    ${total ? `<div class="track ${s.missingCount ? '' : 'done'}"><i data-vars='${esc(JSON.stringify({ '--p': pct + '%' }))}'></i></div>` : ''}
    <div class="chips"><button class="chip on" data-f="all">Alle</button><button class="chip" data-f="missing">Fehlende ${s.missingCount}</button></div>
    <div class="sgrid" id="vols">${s.volumes.map(v => v.book
      ? `<a class="vol" href="#/book/${v.book.id}" data-owned="1">${cover(v.book)}<span class="no">${v.number}</span></a>`
      : `<button class="vol miss ${isWished(v) ? 'wished' : ''}" data-vol="${v.number}" aria-label="Band ${v.number} ${isWished(v) ? 'ist gemerkt' : 'merken'}"><div class="ph">${esc(v.title ?? 'Band ' + v.number)}<em>${isWished(v) ? 'gemerkt' : 'fehlt'}</em></div><span class="no">${v.number}</span></button>`).join('')}
      ${s.volumes.length ? '' : '<p class="note">Keine Bandnummern bekannt.</p>'}</div>
    ${statusNote}
    ${missing.length ? `<p class="note">Tipp auf einen fehlenden Band, um ihn auf die Wunschliste zu setzen.</p>
      <div class="actions">${missing.some(v => !isWished(v)) && missing.length <= 30 ? `<button class="btn" id="wishall">${icons.bookmark} Alle fehlenden merken</button>` : ''}
      <button class="btn ghost" id="share">${icons.share} Als Einkaufsliste teilen</button></div>` : ''}`);
  app.querySelectorAll('[data-f]').forEach(c => c.addEventListener('click', () => {
    app.querySelectorAll('[data-f]').forEach(x => x.classList.toggle('on', x === c));
    app.querySelectorAll('#vols [data-owned]').forEach(v => { v.hidden = c.dataset.f === 'missing'; });
  }));
  const wishVolume = v => post('/api/wishes', { title: v.title ?? `${s.name}, Band ${v.number}`, authors: s.author ? [s.author] : [], seriesName: s.name, seriesVolume: v.number });
  app.querySelectorAll('[data-vol]').forEach(b => b.addEventListener('click', async () => {
    const v = missing.find(x => x.number === Number(b.dataset.vol));
    if (isWished(v)) { go('/wishes'); return; }
    b.disabled = true;
    try { await wishVolume(v); } finally { b.disabled = false; }
    state.wishes = null;
    toast(`Band ${v.number} gemerkt`);
    route();
  }));
  document.getElementById('wishall')?.addEventListener('click', async e => {
    e.currentTarget.disabled = true;
    const open = missing.filter(v => !isWished(v));
    for (const v of open) await wishVolume(v);
    state.wishes = null;
    toast(`${open.length} ${open.length === 1 ? 'Band' : 'Bände'} gemerkt`);
    route();
  });
  document.getElementById('refresh').addEventListener('click', async () => {
    await post(`/api/series/${encodeURIComponent(key)}/catalog`);
    toast('Bandliste wird neu geprüft');
    route();
  });
  document.getElementById('share')?.addEventListener('click', async () => {
    const text = `${s.name}${s.author ? ' (' + s.author + ')' : ''} – fehlende Bände:\n` + missing.map(v => `${v.number}. ${v.title ?? '?'}`).join('\n');
    if (navigator.share) await navigator.share({ title: s.name, text }).catch(() => {});
    else { await navigator.clipboard.writeText(text); toast('In die Zwischenablage kopiert'); }
  });
  if (s.catalogStatus === 'PENDING') {
    const t = setTimeout(() => location.hash.startsWith('#/series/') && route(), 3000);
    cleanup = () => clearTimeout(t);
  }
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
    ${viewHead('Einsortieren', 'Nach Nachname, dann Reihe und Band', `<a class="round" href="#/shelves/new" aria-label="Regal anlegen" title="Regal anlegen">${icons.plus}</a>`)}
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
      <div class="grow"><b>${esc(p.book.title)}</b><small>${esc(p.book.authorSort ?? authors(p.book.authors))}${p.book.seriesName ? ' · ' + esc(p.book.seriesName) + (p.book.seriesVolume ? ' ' + p.book.seriesVolume : '') : ''}</small></div>
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
      <div><b>${s.series}</b><span>Reihen, ${s.completeSeries} komplett</span></div>
      <div><b>${s.missingVolumes}</b><span>Bände fehlen</span></div>
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
      if (t.id === 'logout') { await post('/api/auth/logout', {}); state.me = null; state.books = null; go('/login'); }
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
    state.me = null;
    go('/login');
    toast('Dein Konto ist gelöscht');
  });
}

// ---------------------------------------------------------------- Start

document.addEventListener('click', e => { if (e.target.closest('[data-theme-toggle]')) toggleTheme(); });
window.addEventListener('hashchange', route);
window.addEventListener('unhandledrejection', e => toast(e.reason?.message ?? 'Fehler', true));
route();

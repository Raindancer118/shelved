import { api, get, post, put, del, upload, poll } from './api.js';
import { esc, icons, cover, applyVars, surname, euros, authors, toast, pendingView, colorFor } from './ui.js';
import { liveBarcodeSupported, scanBarcode, prepareImage, capturePhoto } from './camera.js';

const app = document.getElementById('app');
const state = { me: null, books: null, sort: 'author', query: '' };
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
];

async function route() {
  cleanup?.();
  cleanup = null;
  const path = location.hash.replace(/^#/, '') || '/';
  const [re, view, tab] = routes.find(([r]) => r.test(path)) ?? routes[0];
  const args = (path.match(re) ?? []).slice(1).map(decodeURIComponent);
  document.querySelectorAll('.tabbar a').forEach(a => a.classList.toggle('on', a.dataset.tab === tab));
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

function go(path) { location.hash = '#' + path; }

/**
 * Runs an "add" call. If the library already has the book, asks whether this is another copy
 * and repeats the call with anotherCopy. Returns the book, or null if the user said no.
 */
async function addOrCopy(call) {
  try {
    return await call(false);
  } catch (e) {
    if (e.status !== 409) throw e;
    if (!confirm(`„${e.data.title}“ hast du schon. Besitzt du es mehrfach? Dann zähle ich es als weiteres Exemplar.`)) {
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
const invalidate = () => { state.books = null; };

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
  const name = state.me?.name ?? '';
  if (!list.length) {
    paint(`
      <div class="head"><div><h1>Bibliothek</h1><small>Noch leer</small></div><div class="avatar">${esc(name.charAt(0).toUpperCase())}</div></div>
      <div class="empty"><h2>Fang mit einem Regalbrett an</h2>
        <p>Foto vom Regal, Barcode scannen oder von Hand: shelved holt die Daten aus DNB, Google Books und Wikidata.</p>
        <a class="btn" href="#/add">${icons.plus} Bücher hinzufügen</a></div>`);
    return;
  }
  const letters = [...new Set(list.map(b => surname(b).charAt(0).toUpperCase()).filter(l => /[A-ZÄÖÜ]/.test(l)))];
  paint(`
    <div class="head"><div><h1>Bibliothek</h1><small>${list.length} Bücher · ${shelves.length} ${shelves.length === 1 ? 'Regal' : 'Regale'}</small></div>
      <div class="avatar" title="${esc(name)}">${esc(name.charAt(0).toUpperCase())}</div></div>
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
    ['ISBN', b.isbn13],
    ['Neupreis', b.listPriceCents != null && euros(b.listPriceCents)],
    ['Gebraucht', b.usedPriceCents != null && `ca. ${euros(b.usedPriceCents)}`],
    ['Dicke', `${b.estimatedThicknessMm} mm${b.thicknessMm ? '' : ' (geschätzt)'}`],
  ].filter(([, v]) => v);
  const copiesRow = `<dt>Exemplare</dt><dd class="stepper"><button class="round sm" id="less" aria-label="Ein Exemplar weniger" ${b.copies <= 1 ? 'disabled' : ''}>−</button>
      <b>${b.copies}</b><button class="round sm" id="more" aria-label="Ein Exemplar mehr">+</button></dd>`;
  paint(`
    ${backBar('#/', `<a class="round" href="#/book/${b.id}/edit" aria-label="Bearbeiten">${icons.pen}</a>`)}
    <div class="stage"><label title="Cover ändern">${cover(b, { lazy: false })}<input id="coverfile" class="sr" type="file" accept="image/jpeg,image/png"></label></div>
    <h1 class="dtitle">${esc(b.title)}</h1>
    ${b.subtitle ? `<p class="dsub">${esc(b.subtitle)}</p>` : ''}
    <p class="dauthor">${esc(authors(b.authors))}</p>
    ${b.seriesName ? `<a class="serieschip" href="#/series/${encodeURIComponent(b.seriesKey)}"><span>${esc(b.seriesName)}${b.seriesVolume ? ' · Band ' + b.seriesVolume : ''}</span><em>Reihe ›</em></a>` : '<div class="note">Keine Reihe bekannt.</div>'}
    <div class="loc">
      <div><b>${esc(b.location ?? 'Ohne Platz')}</b><small>${b.location ? 'steht hier laut letztem Regalfoto' : 'noch keinem Regal zugeordnet'}${targetLabel && targetLabel !== b.location ? ` · gehört nach <strong>${esc(targetLabel)}</strong>` : ''}</small></div>
      <a class="link" href="#/shelves">Einsortieren</a></div>
    <dl class="facts">${facts.map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join('')}${copiesRow}</dl>
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
    if (!confirm(`„${b.title}“ aus der Bibliothek löschen?`)) return;
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
    go(`/book/${saved.id}`);
  });
}

// ---------------------------------------------------------------- Hinzufügen & Nachschlagen

async function viewAdd() {
  const ai = state.me?.aiAvailable;
  paint(`
    ${backBar('#/')}
    <h1 class="dtitle">Hinzufügen</h1>
    <div class="modes">
      <a class="mode" href="#/add/shelf"><span class="ic">${icons.shelf}</span><span><b>Regal fotografieren</b><small>${ai ? 'Ein Foto pro Regalbrett, die Buchrücken werden gelesen' : 'Nicht eingerichtet (kein API-Key)'}</small></span></a>
      <a class="mode" href="#/add/barcode"><span class="ic">${icons.barcode}</span><span><b>Barcode scannen</b><small>ISBN auf der Rückseite, Daten aus DNB und Google Books</small></span></a>
      <button class="mode" id="bookphoto"><span class="ic">${icons.book}</span><span><b>Buch fotografieren</b><small>Cover oder Rücken eines einzelnen Buchs</small></span></button>
      <a class="mode" href="#/add/manual"><span class="ic">${icons.pen}</span><span><b>Von Hand</b><small>Im Katalog suchen oder selbst eintragen</small></span></a>
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
    ${backBar(purpose === 'add' ? '#/add' : '#/')}
    <h1 class="dtitle">${purpose === 'add' ? 'Barcode scannen' : 'Hab ich das schon?'}</h1>
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
  if (r.owned.length) {
    const b = r.owned[0];
    out.innerHTML = `<div class="result"><a class="row" href="#/book/${b.id}">${cover(b)}<div class="grow"><b>${esc(b.title)}</b><small>${esc(authors(b.authors))}</small></div><span class="tag owned">Hast du</span></a>
      <p class="note">${b.location ? 'Steht bei ' + esc(b.location) : 'Noch ohne Regalplatz'}.</p></div>`;
  } else if (r.metadata) {
    const m = r.metadata;
    out.innerHTML = `<div class="result"><div class="row">${cover({ title: m.title, authors: m.authors })}<div class="grow"><b>${esc(m.title)}</b><small>${esc(authors(m.authors))}</small>
      <small>${esc([m.publisher, m.publishedYear, m.listPriceCents != null && euros(m.listPriceCents)].filter(Boolean).join(' · '))}</small></div>
      <span class="tag new">Neu</span></div><button class="btn block" id="addit">${icons.plus} In die Bibliothek</button></div>`;
    document.getElementById('addit').addEventListener('click', async e => {
      e.target.disabled = true;
      const b = await addOrCopy(another => post('/api/books/isbn', { isbn: r.isbn13, anotherCopy: another }));
      e.target.disabled = false;
      if (!b) return;
      invalidate();
      toast(b.copies > 1 ? `„${b.title}“: jetzt ${b.copies} Exemplare` : `„${b.title}“ hinzugefügt`);
      if (purpose === 'add') { out.innerHTML = ''; document.getElementById('isbn').value = ''; } else go(`/book/${b.id}`);
    });
  } else {
    out.innerHTML = `<div class="result"><p>Zu ${esc(r.isbn13)} kennen DNB, Google Books und Open Library nichts.</p>
      <a class="btn ghost block" href="#/new">Von Hand anlegen</a></div>`;
  }
}

async function viewManual() {
  paint(`
    ${backBar('#/add')}
    <h1 class="dtitle">Im Katalog suchen</h1>
    <form class="form" id="f">
      <div class="field"><label for="t">Titel</label><input id="t" name="t" required></div>
      <div class="field"><label for="a">Autor (optional)</label><input id="a" name="a"></div>
      <button class="btn block" type="submit">${icons.search} Suchen</button>
    </form>
    <div id="out"></div>
    <p class="note"><a class="link" href="#/new">Nicht dabei? Selbst eintragen</a></p>`);
  document.getElementById('f').addEventListener('submit', async e => {
    e.preventDefault();
    const out = document.getElementById('out');
    out.innerHTML = pendingView('Kataloge werden gefragt …');
    const hits = await get(`/api/catalog?title=${encodeURIComponent(e.target.t.value)}&author=${encodeURIComponent(e.target.a.value)}`);
    if (!hits.length) { out.innerHTML = '<p class="note">Keine Treffer.</p>'; return; }
    out.innerHTML = `<div class="list">${hits.map((m, i) => `
      <button class="row" data-i="${i}" type="button">${cover({ title: m.title, authors: m.authors })}
        <div class="grow"><b>${esc(m.title)}</b><small>${esc(authors(m.authors))}</small><small>${esc([m.publisher, m.publishedYear, m.isbn13].filter(Boolean).join(' · '))}</small></div>
        <span class="tag">${esc(m.source)}</span></button>`).join('')}</div>`;
    applyVars(out);
    out.querySelectorAll('[data-i]').forEach(btn => btn.addEventListener('click', async () => {
      btn.disabled = true;
      const b = await addOrCopy(another => post(`/api/books/from-catalog?anotherCopy=${another}`, hits[Number(btn.dataset.i)]));
      btn.disabled = false;
      if (!b) return;
      invalidate();
      toast(`„${b.title}“ hinzugefügt`);
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
      ${it.status === 'OWNED' ? '<span class="pos">✓</span>' : `<input class="check" type="checkbox" data-i="${it.index}" ${it.status === 'NEW' ? 'checked' : ''} aria-label="übernehmen">`}
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
    go(scan.shelfId ? '/shelves' : '/');
  });
}

// ---------------------------------------------------------------- Reihen

async function viewSeriesList() {
  const list = await get('/api/series');
  if (!list.length) {
    paint(`<div class="head"><div><h1>Reihen</h1></div></div><div class="empty"><h2>Noch keine Reihen</h2><p>Sobald Bücher einer Reihe in deiner Bibliothek sind, siehst du hier, welche Bände fehlen.</p></div>`);
    return;
  }
  const missing = list.reduce((s, r) => s + r.missingCount, 0);
  paint(`
    <div class="head"><div><h1>Reihen</h1><small>${list.length} Reihen · ${missing} Bände fehlen</small></div>
      <button class="round" id="recheck" aria-label="Reihen neu prüfen" title="Reihen neu prüfen">↻</button></div>
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
    toast(`${r.books} Bücher werden neu geprüft (Nationalbibliothek, Wikidata)`);
    setTimeout(() => location.hash === '#/series' && route(), 6000);
  });
  if (list.some(r => r.catalogStatus === 'PENDING')) {
    const t = setTimeout(() => location.hash === '#/series' && route(), 4000);
    cleanup = () => clearTimeout(t);
  }
}

async function viewSeries(key) {
  const s = await get(`/api/series/${encodeURIComponent(key)}`);
  const total = s.knownTotal;
  const pct = total ? Math.round(100 * s.ownedCount / total) : 0;
  const source = { WIKIDATA: 'Bandliste aus Wikidata', CLAUDE: 'Bandliste per KI-Recherche' }[s.catalogSource];
  const statusNote = s.catalogStatus === 'PENDING' ? '<p class="note">Bandliste wird geladen …</p>'
    : s.catalogStatus === 'FAILED' ? `<p class="note warn">${esc(s.catalogNote ?? 'Keine Bandliste gefunden')}. Lücken zwischen deinen Bänden siehst du trotzdem.</p>`
    : `<p class="note">${esc(source ?? '')}${s.catalogNote ? ' · ' + esc(s.catalogNote) : ''}</p>`;
  const missing = s.volumes.filter(v => !v.book);
  paint(`
    ${backBar('#/series', `<button class="round" id="refresh" aria-label="Bandliste neu prüfen">${icons.more}</button>`)}
    <small class="dauthor">${esc(s.author ?? '')}</small>
    <h1 class="dtitle">${esc(s.name)}</h1>
    <div class="big"><b>${s.ownedCount}</b><span>${total ? `von ${total} Bänden` : 'Bände'}${s.missingCount ? ` · ${s.missingCount} fehlen` : ''}</span></div>
    ${total ? `<div class="track ${s.missingCount ? '' : 'done'}"><i data-vars='${esc(JSON.stringify({ '--p': pct + '%' }))}'></i></div>` : ''}
    <div class="chips"><button class="chip on" data-f="all">Alle</button><button class="chip" data-f="missing">Fehlende ${s.missingCount}</button></div>
    <div class="sgrid" id="vols">${s.volumes.map(v => v.book
      ? `<a class="vol" href="#/book/${v.book.id}" data-owned="1">${cover(v.book)}<span class="no">${v.number}</span></a>`
      : `<div class="vol miss"><div class="ph">${esc(v.title ?? 'Band ' + v.number)}<em>fehlt</em></div><span class="no">${v.number}</span></div>`).join('')}
      ${s.volumes.length ? '' : '<p class="note">Keine Bandnummern bekannt.</p>'}</div>
    ${statusNote}
    ${missing.length ? '<div class="actions"><button class="btn ghost" id="share">Fehlende als Einkaufsliste teilen</button></div>' : ''}`);
  app.querySelectorAll('[data-f]').forEach(c => c.addEventListener('click', () => {
    app.querySelectorAll('[data-f]').forEach(x => x.classList.toggle('on', x === c));
    app.querySelectorAll('#vols [data-owned]').forEach(v => { v.hidden = c.dataset.f === 'missing'; });
  }));
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
    paint(`<div class="head"><div><h1>Regale</h1></div></div>
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
    <div class="head"><div><h1>Einsortieren</h1><small>nach Nachname, dann Reihe und Band</small></div><a class="round" href="#/shelves/new" aria-label="Regal anlegen">${icons.plus}</a></div>
    <div class="summary"><div><b>${plan.toPlace}</b>neu einsortieren</div><div><b>${plan.toMove}</b>umstellen</div><div><b>${plan.overflow.length}</b>passen nicht</div></div>
    <div class="seg" role="tablist">${shelves.map((s, i) => `<button role="tab" class="${i === currentShelf ? 'on' : ''}" data-s="${i}">${esc(s.name)}</button>`).join('')}</div>
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
    <div class="lbl"><b>Boden ${bd.board}</b><span>${est ? `≈ ${bd.widthMm / 10} cm (geschätzt) · ` : ''}${bd.books.length} Bücher · ${pct} % voll${free > 0 ? ` · ca. ${free} cm frei` : ''}</span></div>
    <div class="strip" aria-hidden="true">${bd.books.map(p => `<i class="${p.status === 'NEW' ? 'new' : p.status === 'MOVE' ? 'move' : ''}" title="${esc(p.book.title)}"
      data-vars='${esc(JSON.stringify({ '--w': (p.thicknessMm * scale).toFixed(2) + '%', '--h': (70 + (p.book.title.length % 5) * 6) + '%', '--c': colorFor(p.book.title) }))}'></i>`).join('')}</div>
    ${first ? `<p class="note">${esc(surname(first) || first.title)} bis ${esc(surname(last) || last.title)}</p>` : '<p class="note">leer</p>'}
    <div class="list">${bd.books.map(p => `<a class="row" href="#/book/${p.book.id}" data-status="${p.status}">
      <span class="pos">${String(p.position).padStart(2, '0')}</span>
      <div class="grow"><b>${esc(p.book.title)}</b><small>${esc(p.book.authorSort ?? authors(p.book.authors))}${p.book.seriesName ? ' · ' + esc(p.book.seriesName) + (p.book.seriesVolume ? ' ' + p.book.seriesVolume : '') : ''}</small></div>
      ${p.status === 'NEW' ? '<span class="tag new">neu</span>' : p.status === 'MOVE' ? `<span class="tag move">von ${esc(p.from)}</span>` : ''}</a>`).join('')}</div>
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
    if (!confirm(`„${existing.name}“ löschen? Die Bücher bleiben in der Bibliothek, nur ihr Platz wird vergessen.`)) return;
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
    <div class="head"><div><h1>Statistik</h1></div></div>
    <small class="dauthor">Wert deiner Bibliothek (Neupreis)</small>
    <div class="value">${esc(eur)}<small>,${esc(cent)}</small></div>
    <p class="dauthor">gebraucht geschätzt ca. ${esc(euros(s.usedValueCents))}</p>
    <div class="kpis">
      <div><b>${s.books}</b><span>Bücher</span></div>
      <div><b>${s.shelves}</b><span>Regale</span></div>
      <div><b>${s.series}</b><span>Reihen, ${s.completeSeries} komplett</span></div>
      <div><b>${s.missingVolumes}</b><span>Bände fehlen</span></div>
      <div><b>${s.pages.toLocaleString('de-DE')}</b><span>Seiten</span></div>
      <div><b>${s.books - s.withPrice}</b><span>ohne bekannten Preis</span></div>
    </div>
    ${s.topAuthors.length ? `<h3 class="sec">Am häufigsten</h3><div class="list">${s.topAuthors.map(a => `<div class="row"><div class="grow"><b>${esc(a.author)}</b></div><span class="tag">${a.books}</span></div>`).join('')}</div>` : ''}
    <p class="note">Neupreise kommen aus der Deutschen Nationalbibliothek. Der Gebrauchtwert ist eine Faustregel: Taschenbücher ca. 30 %, Hardcover ca. 40 % vom Neupreis, Bücher der letzten zwei Jahre etwas mehr.</p>`);
}

// ---------------------------------------------------------------- Start

window.addEventListener('hashchange', route);
window.addEventListener('unhandledrejection', e => toast(e.reason?.message ?? 'Fehler', true));
get('/api/me').then(me => { state.me = me; }).catch(() => {}).finally(route);

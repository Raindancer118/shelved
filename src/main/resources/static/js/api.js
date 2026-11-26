// Thin fetch wrapper: CSRF header, JSON, readable errors, login redirect.
export const PUBLIC = /^\/(login|register|verify|forgot|reset|invite)(\?|$)/;
let csrfReady = null;

function cookie(name) {
  const hit = document.cookie.split('; ').find(c => c.startsWith(name + '='));
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : null;
}

async function ensureCsrf() {
  if (cookie('XSRF-TOKEN')) return;
  csrfReady ??= fetch('/api/csrf', { credentials: 'same-origin' }).finally(() => { csrfReady = null; });
  await csrfReady;
}

export class ApiError extends Error {
  constructor(status, message, data = null) { super(message); this.status = status; this.data = data; }
}

// Immediate feedback: the control that was just pressed shows it is working until the request it started is
// answered, and can't be pressed twice meanwhile. Works for every button without wiring each one.
const PRESSABLE = 'button, .btn, .round, a.link, .seg button';
let pressed = null;
const pending = new WeakMap();

function busy(el, on) {
  const n = (pending.get(el) ?? 0) + (on ? 1 : -1);
  pending.set(el, Math.max(0, n));
  el.classList.toggle('busy', n > 0);
  if (n > 0) el.setAttribute('aria-busy', 'true'); else el.removeAttribute('aria-busy');
}

if (typeof document !== 'undefined') {
  document.addEventListener('click', e => {
    const el = e.target.closest?.(PRESSABLE);
    if (el) pressed = { el, at: performance.now() };
  }, true);
  document.addEventListener('submit', e => {
    const el = e.submitter ?? e.target.querySelector('[type=submit]');
    if (el) pressed = { el, at: performance.now() };
  }, true);
  // choosing a photo: resizing it takes a moment before any request starts, so the control is busy right away
  document.addEventListener('change', e => {
    if (e.target.type !== 'file' || !e.target.files?.length) return;
    const el = e.target.closest('label') ?? e.target;
    busy(el, true);
    const release = () => busy(el, false);
    pressed = { el, at: performance.now(), release };
    setTimeout(() => { if (pressed?.release === release) { pressed = null; release(); } }, 8000);
  }, true);
}

/** The control behind a request started right after a press, marked busy; returns how to release it. */
function claimPress() {
  if (!pressed || performance.now() - pressed.at > 1500 || !pressed.el.isConnected) return () => {};
  const { el, release } = pressed;
  if (release) {
    pressed = null;
    return release; // already busy since the photo was chosen
  }
  busy(el, true);
  return () => busy(el, false);
}

export async function api(path, { method = 'GET', json, form } = {}) {
  const headers = { Accept: 'application/json' };
  let body;
  if (method !== 'GET') {
    await ensureCsrf();
    headers['X-XSRF-TOKEN'] = cookie('XSRF-TOKEN') ?? '';
  }
  if (json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
  if (form) body = form;
  const release = claimPress();
  let res;
  try {
    res = await fetch(path, { method, headers, body, credentials: 'same-origin' });
  } finally {
    release();
  }
  if (res.status === 401 && !path.startsWith('/api/auth/')) {
    const here = location.hash.replace(/^#/, '') || '/';
    if (!PUBLIC.test(here)) location.hash = '#/login?next=' + encodeURIComponent(here);
    throw new ApiError(401, 'Bitte melde dich an');
  }
  if (res.status === 204) return null;
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, data?.error ?? `Fehler ${res.status}`, data);
  return data;
}

export const get = path => api(path);
export const post = (path, json) => api(path, { method: 'POST', json });
export const put = (path, json) => api(path, { method: 'PUT', json });
export const del = path => api(path, { method: 'DELETE' });
export const upload = (path, form, method = 'POST') => api(path, { method, form });

/** Polls until the check says done. Gives up after `limitMs`. */
export async function poll(fn, done, { every = 1500, limitMs = 240000 } = {}) {
  const until = Date.now() + limitMs;
  for (;;) {
    const v = await fn();
    if (done(v)) return v;
    if (Date.now() > until) throw new ApiError(504, 'Das dauert ungewöhnlich lange, versuch es später nochmal');
    await new Promise(r => setTimeout(r, every));
  }
}

// Thin fetch wrapper: CSRF header, JSON, readable errors, login redirect.
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

export async function api(path, { method = 'GET', json, form } = {}) {
  const headers = { Accept: 'application/json' };
  let body;
  if (method !== 'GET') {
    await ensureCsrf();
    headers['X-XSRF-TOKEN'] = cookie('XSRF-TOKEN') ?? '';
  }
  if (json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
  if (form) body = form;
  const res = await fetch(path, { method, headers, body, credentials: 'same-origin' });
  if (res.status === 401) {
    location.href = '/oauth2/authorization/authentik';
    throw new ApiError(401, 'Bitte neu anmelden');
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

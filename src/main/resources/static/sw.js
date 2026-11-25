// Keeps the covers of the library and the wish list on the device. Cover URLs carry ?v=<hash of the file>,
// so a cached response never goes stale: a new cover is a new URL. Nothing else is touched.
const CACHE = 'shelved-covers-v2';
const COVER = /^\/api\/((books|wishes)\/[\w-]+\/cover|covers\/\d{13})$/;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil((async () => {
  for (const name of await caches.keys()) {
    if (name.startsWith('shelved-covers') && name !== CACHE) await caches.delete(name);
  }
  await self.clients.claim();
})()));

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin || !COVER.test(url.pathname)) return;
  e.respondWith(caches.open(CACHE).then(async cache => {
    const hit = await cache.match(e.request);
    if (hit) return hit;
    const res = await fetch(e.request);
    if (res.ok) cache.put(e.request, res.clone());
    return res;
  }));
});

self.addEventListener('message', e => {
  const { type, urls } = e.data ?? {};
  if (type === 'clear') e.waitUntil(caches.delete(CACHE));
  if (type === 'warm' && Array.isArray(urls)) e.waitUntil(warm(urls));
});

/** Loads every cover the library and wish list show, and drops the ones they no longer show. */
async function warm(urls) {
  const cache = await caches.open(CACHE);
  const keep = new Set(urls.map(u => new URL(u, self.location.origin).href));
  for (const req of await cache.keys()) {
    if (!keep.has(req.url)) await cache.delete(req);
  }
  const missing = [];
  for (const u of keep) {
    if (!await cache.match(u)) missing.push(u);
  }
  // four at a time, so warming never crowds out what the page itself is loading
  for (let i = 0; i < missing.length; i += 4) {
    await Promise.all(missing.slice(i, i + 4).map(u =>
      fetch(u, { credentials: 'same-origin' }).then(r => r.ok ? cache.put(u, r) : null).catch(() => null)));
  }
}

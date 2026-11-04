/* Service worker: offline app shell + Android share-target receiver. */
const VERSION = 'dev'; // replaced with the commit SHA on deploy
const SHELL = `shell-${VERSION}`;
const SHARED = 'shared-file';
const ASSETS = [
  './', 'index.html', 'app.js', 'converter.js', 'style.css', 'manifest.webmanifest',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-192.png', 'icons/maskable-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('message', (e) => {
  if (e.data === 'version' && e.source) e.source.postMessage({ version: VERSION });
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) {
      if (k.startsWith('shell-') && k !== SHELL) await caches.delete(k);
    }
    await self.clients.claim();
  })());
});

async function receiveShare(request) {
  const form = await request.formData();
  let file = form.get('csv');
  let filename = file && file.name ? file.name : 'manabox.csv';
  // Some apps share the CSV as text instead of a file.
  if (!file || typeof file === 'string') {
    const text = form.get('text') || (typeof file === 'string' ? file : '');
    file = new Blob([text], { type: 'text/csv' });
  }
  const cache = await caches.open(SHARED);
  await cache.put('latest', new Response(file, {
    headers: { 'X-Filename': encodeURIComponent(filename) },
  }));
  return Response.redirect(new URL('./?shared=1', self.registration.scope).href, 303);
}

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method === 'POST' && url.pathname.endsWith('/share-target')) {
    e.respondWith(receiveShare(e.request));
    return;
  }
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return;
  // Network first so updates land quickly; fall back to cache offline.
  e.respondWith((async () => {
    try {
      const res = await fetch(e.request, { cache: 'no-cache' });
      if (res.ok) (await caches.open(SHELL)).put(e.request, res.clone());
      return res;
    } catch {
      return (await caches.match(e.request, { ignoreSearch: true })) ||
             (await caches.match('index.html'));
    }
  })());
});

// Service worker: lets the timeline be installed as an app and open quickly.
// - The page itself: network first (so updates show straight away), cached copy if offline.
// - Libraries and icons: served from cache, refreshed in the background.
// - Your data (Supabase), place lookups and map tiles are never cached here.
const CACHE = 'timeline-v1';
const SHELL = ['./', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // The app page: always try the network first so new versions appear immediately.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => { const copy = res.clone(); caches.open(CACHE).then((c) => c.put('./', copy)); return res; })
        .catch(() => caches.match('./'))
    );
    return;
  }

  // Our own small files and the map/database libraries: cache, refreshed in the background.
  const sameOrigin = url.origin === self.location.origin;
  if (sameOrigin || url.hostname === 'cdn.jsdelivr.net') {
    event.respondWith(
      caches.open(CACHE).then(async (c) => {
        const cached = await c.match(req);
        const fresh = fetch(req).then((res) => { if (res.ok) c.put(req, res.clone()); return res; }).catch(() => cached);
        return cached || fresh;
      })
    );
  }
  // Everything else (Supabase, OpenStreetMap, tiles) goes straight to the network.
});

// SPLITSTAK service worker
// Network-first for index/navigations so updates land immediately when online.
// Cache-first for static assets (icons, manifest) for instant loads.
// App stays usable offline once index.html has been visited at least once.

const CACHE_NAME = 'splitstak-v4';
const PRECACHE = [
  '/',
  '/index.html',
  '/manifest.json',
  '/icon-192.png',
  '/icon-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(PRECACHE))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(
      keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))
    )).then(() => self.clients.claim())
  );
});

// Web Push from the splitstak-push Cloudflare Worker. One push per
// phase (warning, finish) lands a single notification on the lock screen.
// The phase-distinguishing body text ("⚠ 10 seconds left" vs
// "✓ Rest complete") is the only per-event signal we control — Chrome
// on Android delegates everything else to the user-managed
// NotificationChannel: sound, vibration, importance, lock-screen
// visibility. So we don't ship vibration patterns (Android 8+ ignores
// them in favour of channel settings) and we don't try to spawn more
// than one notification per phase.
//
// Phase identification: pushes have no payload, so the SW reads the
// timer schedule (warnTime / finishTime wall-clock timestamps) the page
// stored in IndexedDB on /schedule and picks the closest phase to "now".
//
// Visible-client suppression remains, plus the page races a /complete
// cancel to the worker as a deterministic backup — together they ensure
// we never double-fire when the app is open.
function idbOpen() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('splitstak', 1);
    req.onupgradeneeded = () => { req.result.createObjectStore('kv'); };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
function idbGetTimerSchedule() {
  return idbOpen().then((db) => new Promise((resolve, reject) => {
    const tx = db.transaction('kv', 'readonly');
    const req = tx.objectStore('kv').get('timer-schedule');
    req.onsuccess = () => resolve(req.result || null);
    req.onerror = () => reject(req.error);
  }));
}

self.addEventListener('push', (event) => {
  event.waitUntil((async () => {
    const allClients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const visibleClient = allClients.find((c) => c.visibilityState === 'visible');
    if (visibleClient) {
      try { visibleClient.postMessage({ type: 'splitstak-push' }); } catch (e) {}
      return;
    }

    // Identify phase by which scheduled timestamp is closest to right now.
    let body = 'Rest timer';
    try {
      const schedule = await idbGetTimerSchedule();
      if (schedule) {
        const now = Date.now();
        const warnDist = schedule.warnTime != null ? Math.abs(now - schedule.warnTime) : Infinity;
        const finishDist = schedule.finishTime != null ? Math.abs(now - schedule.finishTime) : Infinity;
        if (finishDist <= warnDist && finishDist < 10000) {
          body = '✓ Rest complete';
        } else if (warnDist < 10000) {
          body = '⚠ 10 seconds left';
        }
      }
    } catch (e) {}

    await self.registration.showNotification('SPLITSTAK', {
      body,
      tag: 'splitstak-' + Date.now(),
      silent: false,
      icon: '/icon-192.png',
      badge: '/icon-192.png',
      data: { ts: Date.now() },
    });
  })());
});

// Tapping the lock-screen notification jumps back into the app.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const existing = clients.find((c) => c.url.includes('splitstak'));
    if (existing) {
      try { existing.focus(); } catch (e) {}
      return;
    }
    try { await self.clients.openWindow('/'); } catch (e) {}
  })());
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  const isNavigation = req.mode === 'navigate' ||
                       url.pathname === '/' ||
                       url.pathname === '/index.html';

  if (isNavigation) {
    // Network-first, fall back to cached index.html when offline.
    event.respondWith(
      fetch(req).then((res) => {
        const copy = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
        return res;
      }).catch(() => caches.match(req).then((m) => m || caches.match('/index.html')))
    );
    return;
  }

  // Cache-first for icons, manifest, fonts, etc.
  event.respondWith(
    caches.match(req).then((cached) => cached || fetch(req).then((res) => {
      if (res && res.ok && (res.type === 'basic' || res.type === 'cors')) {
        const copy = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
      }
      return res;
    }))
  );
});

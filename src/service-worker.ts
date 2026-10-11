/// <reference lib="webworker" />
import { build, files, version } from '$service-worker';

const worker = self as unknown as ServiceWorkerGlobalScope;
const cacheName = `fytorium-${version}`;
const assets = [...build, ...files];
const assetPaths = new Set(assets);

worker.addEventListener('push', event => {
  let payload;
  try {
    const value = event.data?.json();
    if (value && typeof value === 'object' && ['title', 'body', 'tag', 'url'].every(key => typeof value[key] === 'string')) payload = value;
  } catch { /* Still show a visible notification for malformed pushes. */ }
  event.waitUntil(worker.registration.showNotification(payload?.title ?? 'Fytorium', {
    body: payload?.body ?? 'Open Today to check your plants.',
    tag: payload?.tag ?? 'fytorium-fallback',
    icon: '/icon-192.png',
    data: { url: payload?.url ?? '/' },
  }));
});

worker.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil((async () => {
    const url = new URL(event.notification.data?.url ?? '/', worker.location.origin);
    if (url.origin !== worker.location.origin) return;
    const clients = await worker.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const client = clients.find(client => new URL(client.url).origin === url.origin) as WindowClient | undefined;
    if (client) {
      await client.navigate(url.href);
      await client.focus();
    } else await worker.clients.openWindow(url.href);
  })());
});

worker.addEventListener('install', event => {
  event.waitUntil(caches.open(cacheName).then(cache => cache.addAll(assets)));
});

worker.addEventListener('activate', event => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) {
      if (name.startsWith('fytorium-') && name !== cacheName) await caches.delete(name);
    }
    await worker.clients.claim();
  })());
});

worker.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  // Only static assets are cached. Household pages and API responses stay on the server.
  if (event.request.method !== 'GET' || url.origin !== worker.location.origin || !assetPaths.has(url.pathname)) return;
  event.respondWith((async () => {
    const cache = await caches.open(cacheName);
    return await cache.match(event.request) ?? fetch(event.request);
  })());
});

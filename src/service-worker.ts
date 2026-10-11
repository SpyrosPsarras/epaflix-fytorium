/// <reference lib="webworker" />
import { build, files, version } from '$service-worker';

const worker = self as unknown as ServiceWorkerGlobalScope;
const cacheName = `fytorium-${version}`;
const assets = [...build, ...files];
const assetPaths = new Set(assets);

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

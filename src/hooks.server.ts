import type { Handle } from '@sveltejs/kit';
import { building } from '$app/environment';
import { startCareWorker } from '$lib/server/care/worker';
import { startNameWorker } from '$lib/server/names';
import { identity } from '$lib/server/auth';
import { requests, duration } from '$lib/server/metrics';

if (!building) {
  startCareWorker();
  startNameWorker();
}

export const handle: Handle = async ({ event, resolve }) => {
  const start = performance.now();
  let status = 500;
  try {
    const operational = event.route.id === '/healthz' || event.route.id === '/metrics';
    event.locals.identity = operational ? null : await identity(event.cookies);
    const response = await resolve(event);
    status = response.status;
    response.headers.set('Cache-Control', 'no-store');
    response.headers.set('X-Content-Type-Options', 'nosniff');
    response.headers.set('Referrer-Policy', 'no-referrer');
    return response;
  } finally {
    const method = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].includes(event.request.method) ? event.request.method : 'OTHER';
    const labels = { route: event.route.id ?? 'unmatched', method, status: String(status) };
    requests.inc(labels);
    duration.observe(labels, (performance.now() - start) / 1000);
  }
};

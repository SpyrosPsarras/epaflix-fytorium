import { error, isHttpError, isRedirect, type Handle } from '@sveltejs/kit';
import { building } from '$app/environment';
import { startPushWorker } from '$lib/server/notifications/worker';
import { startWeatherWorker } from '$lib/server/weather/worker';
import { startCareWorker } from '$lib/server/care/worker';
import { startNameWorker } from '$lib/server/names';
import { identity } from '$lib/server/auth';
import { config } from '$lib/server/config';
import { authorizeRoute, sitterView } from '$lib/server/policy';
import { requests, duration } from '$lib/server/metrics';

if (!building) {
  startPushWorker();
  startCareWorker();
  startWeatherWorker();
  startNameWorker();
}

export const handle: Handle = async ({ event, resolve }) => {
  const start = performance.now();
  let status = 500;
  try {
    if (event.route.id?.startsWith('/__test/') && !config().testClockEnabled) error(404, 'Not found');
    const operational = event.route.id === '/healthz' || event.route.id === '/metrics';
    event.locals.identity = operational ? null : await identity(event.cookies);
    const projection = await authorizeRoute(event.locals.identity, event.route.id, event.request.method, event.params);
    let response = await resolve(event);
    if (event.locals.identity?.member.role === 'sitter' && response.ok && response.headers.get('content-type')?.includes('application/json')
      && !event.isDataRequest && projection) {
      const body = JSON.stringify(sitterView(await response.json(), projection));
      const headers = new Headers(response.headers);
      headers.delete('content-length');
      response = new Response(body, { status: response.status, headers });
    }
    status = response.status;
    response.headers.set('Cache-Control', 'no-store');
    response.headers.set('X-Content-Type-Options', 'nosniff');
    response.headers.set('Referrer-Policy', 'no-referrer');
    return response;
  } catch (cause) {
    if (isHttpError(cause) || isRedirect(cause)) status = cause.status;
    throw cause;
  } finally {
    const method = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].includes(event.request.method) ? event.request.method : 'OTHER';
    const labels = { route: event.route.id ?? 'unmatched', method, status: String(status) };
    requests.inc(labels);
    duration.observe(labels, (performance.now() - start) / 1000);
  }
};

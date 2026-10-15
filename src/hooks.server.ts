import { error, isHttpError, isRedirect, json, type Handle } from '@sveltejs/kit';
import { lt } from 'semver';
import { building } from '$app/environment';
import { startPushWorker } from '$lib/server/notifications/worker';
import { startWeatherWorker } from '$lib/server/weather/worker';
import { startCareWorker } from '$lib/server/care/worker';
import { startNameWorker } from '$lib/server/names';
import { identity } from '$lib/server/auth';
import { bearerDevice, startAppTokenMaintenance } from '$lib/server/app-auth';
import { checkOrigin } from '$lib/server/sites';
import { config, validAppVersion } from '$lib/server/config';
import { authorizeRoute, sitterView } from '$lib/server/policy';
import { requests, duration } from '$lib/server/metrics';

if (!building) {
  startAppTokenMaintenance();
  startPushWorker();
  startCareWorker();
  startWeatherWorker();
  startNameWorker();
}

export const handle: Handle = async ({ event, resolve }) => {
  const start = performance.now();
  let status = 500;
  try {
    const minimum = config().minAppVersion;
    const version = event.request.headers.get('X-App-Version');
    if (minimum && version !== null && (!validAppVersion(version) || lt(version, minimum))
      && (event.url.pathname === '/api' || event.url.pathname.startsWith('/api/'))
      && event.url.pathname !== '/api/meta' && !event.url.pathname.startsWith('/api/app/')) {
      status = 426;
      return json({ error: 'update_required', minAppVersion: minimum }, { status, headers: {
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
      } });
    }
    if (event.route.id?.startsWith('/__test/') && !config().testClockEnabled) error(404, 'Not found');
    const operational = event.route.id === '/healthz' || event.route.id === '/metrics';
    event.locals.identity = operational ? null : await identity(event.cookies, event.request);
    if (!operational && event.route.id?.startsWith('/api/') && event.request.headers.has('authorization') && !event.locals.identity) {
      status = 401;
      return json({ error: 'invalid_token' }, { status, headers: {
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
      } });
    }
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(event.request.method) && !bearerDevice(event.request)) {
      checkOrigin(event.request);
      const contentType = event.request.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
      // SvelteKit's form guard moved here so only verified bearer requests can bypass it.
      if (['application/x-www-form-urlencoded', 'multipart/form-data', 'text/plain'].includes(contentType ?? '')
        && event.request.headers.get('origin') !== event.url.origin) error(403, 'Cross-origin request refused');
    }
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
    if (!response.headers.has('Cache-Control')) response.headers.set('Cache-Control', 'no-store');
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

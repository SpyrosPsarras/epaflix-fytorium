import { expect, test } from 'vitest';
import { spawn } from 'node:child_process';
import { TEST_CLIENT_SECRET } from './oidc-provider';
import { createApiHarness } from './harness';

const harness = createApiHarness();
const login = harness.login;
let ownerIdentity: unknown;

test('first OIDC login creates the household and its owner', async () => {
  const { callback, cookie } = await login();
  expect(callback.status).toBe(303);
  expect(callback.headers.get('location')).toBe('/');
  const response = await fetch(harness.base + '/api/me', { headers: { cookie } });
  expect(response.status).toBe(200);
  ownerIdentity = await response.json();
  expect(ownerIdentity).toMatchObject({
    household: { id: expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/), name: 'My household' },
    member: { role: 'owner' },
  });
  const home = await fetch(harness.base, { headers: { cookie } });
  expect(await home.text()).toContain('No plants yet');
  expect(callback.headers.getSetCookie().join(';')).toContain('HttpOnly');
  expect(callback.headers.getSetCookie().join(';')).toContain('SameSite=Lax');
});

test('later login by the same OIDC subject reuses the member and household', async () => {
  const { cookie } = await login();
  const response = await fetch(harness.base + '/api/me', { headers: { cookie } });
  expect(await response.json()).toEqual(ownerIdentity);
});

test('an unknown subject cannot create another household or session', async () => {
  const { callback, cookie } = await login('synthetic-outsider');
  expect(callback.headers.get('location')).toBe('/auth/not-member');
  expect(cookie).toBe('');
  const refused = await fetch(harness.base + '/auth/not-member');
  expect(refused.status).toBe(403);
  expect(await refused.text()).toContain('Not a member');
  expect((await fetch(harness.base + '/api/me')).status).toBe(401);
});

test('callback transactions cannot be replayed', async () => {
  const { callbackUrl, transactionCookie } = await login();
  const replay = await fetch(callbackUrl, { redirect: 'manual', headers: { cookie: transactionCookie } });
  expect(replay.headers.get('location')).toBe('/auth/failed');
  expect(replay.headers.getSetCookie().some(cookie => cookie.startsWith('fytorium_session='))).toBe(false);
});

test('a callback with the wrong state does not log in', async () => {
  const start = await fetch(harness.base + '/auth/login', { redirect: 'manual' });
  const cookie = start.headers.getSetCookie()[0].split(';')[0];
  const authorize = await fetch(start.headers.get('location')!, { redirect: 'manual' });
  const url = new URL(authorize.headers.get('location')!);
  url.searchParams.set('state', 'wrong-state');
  const callback = await fetch(url, { redirect: 'manual', headers: { cookie } });
  expect(callback.headers.get('location')).toBe('/auth/failed');
  expect((await fetch(harness.base + '/api/me', { headers: { cookie } })).status).toBe(401);
});

test('OIDC failures log the message and error code without provider details or secrets', async () => {
  const start = await fetch(harness.base + '/auth/login', { redirect: 'manual' });
  const transactionCookie = start.headers.getSetCookie()[0].split(';')[0];
  const authorization = new URL(start.headers.get('location')!);
  const callbackUrl = new URL('/auth/callback', harness.base);
  callbackUrl.searchParams.set('state', authorization.searchParams.get('state')!);
  callbackUrl.searchParams.set('error', 'access_denied');
  callbackUrl.searchParams.set('error_description', 'synthetic-private-provider-detail');
  const response = await fetch(callbackUrl, { redirect: 'manual', headers: { cookie: transactionCookie } });
  expect(response.headers.get('location')).toBe('/auth/failed');
  await expect.poll(() => harness.appOutput).toContain("oidcError: 'access_denied'");
  expect(harness.appOutput).toContain('message:');
  expect(harness.appOutput).not.toContain('synthetic-private-provider-detail');
  expect(harness.appOutput).not.toContain(TEST_CLIENT_SECRET);
  expect(harness.appOutput).not.toContain(transactionCookie.split('=')[1]);
});

test('logout revokes the server-side session and rejects cross-origin form posts', async () => {
  const { cookie } = await login();
  const crossOrigin = await fetch(harness.base + '/auth/logout', { method: 'POST', redirect: 'manual', headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/x-www-form-urlencoded' }, body: '' });
  expect(crossOrigin.status).toBe(403);
  expect((await fetch(harness.base + '/api/me', { headers: { cookie } })).status).toBe(200);
  const logout = await fetch(harness.base + '/auth/logout', { method: 'POST', redirect: 'manual', headers: { cookie, origin: harness.base } });
  expect(logout.status).toBe(303);
  expect((await fetch(harness.base + '/api/me', { headers: { cookie } })).status).toBe(401);
});

test('PWA manifest, generated icons and service worker are served', async () => {
  const response = await fetch(harness.base + '/manifest.webmanifest');
  expect(response.status).toBe(200);
  const manifest = await response.json();
  expect(manifest).toMatchObject({ name: 'Fytorium', start_url: '/', display: 'standalone' });
  expect(manifest.icons).toEqual(expect.arrayContaining([
    expect.objectContaining({ src: '/icon-192.png', sizes: '192x192' }),
    expect.objectContaining({ src: '/icon-512.png', sizes: '512x512' }),
    expect.objectContaining({ src: '/icon.svg', sizes: 'any' }),
  ]));
  for (const path of ['/icon-192.png', '/icon-512.png', '/icon.svg', '/service-worker.js']) {
    expect((await fetch(harness.base + path)).status).toBe(200);
  }
  expect(await (await fetch(harness.base)).text()).toContain('/service-worker.js');
});

test('health checks the database and metrics expose default and HTTP observations', async () => {
  expect(await (await fetch(harness.base + '/healthz')).json()).toEqual({ status: 'ok' });
  const response = await fetch(harness.base + '/metrics');
  expect(response.status).toBe(200);
  expect(response.headers.get('content-type')).toContain('text/plain');
  const metrics = await response.text();
  expect(metrics).toContain('process_resident_memory_bytes');
  expect(metrics).toContain('fytorium_http_requests_total');
  expect(metrics).toContain('fytorium_http_request_duration_seconds_bucket');
});

test('production cannot reach or enable the test clock', async () => {
  expect((await fetch(harness.base + '/__test/clock')).status).toBe(404);
  expect((await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-01-01T00:00:00Z' }) })).status).toBe(404);
  const refused = spawn('node', ['scripts/start.mjs'], {
    env: { ...process.env, NODE_ENV: 'production', FYTORIUM_TEST_CLOCK: 'true' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  refused.stdout!.on('data', data => output += data);
  refused.stderr!.on('data', data => output += data);
  const status = await new Promise<number | null>((resolve, reject) => {
    refused.once('error', reject);
    refused.once('exit', resolve);
  });
  expect(status).not.toBe(0);
  expect(output).toContain('FYTORIUM_TEST_CLOCK requires NODE_ENV=test');
});

test('a database outage returns health 503 without killing the app or metrics', async () => {
  await harness.stopPostgres();
  const headers = { cookie: 'fytorium_session=invalid-session' };
  expect((await fetch(harness.base + '/healthz', { headers })).status).toBe(503);
  expect((await fetch(harness.base + '/metrics', { headers })).status).toBe(200);
});

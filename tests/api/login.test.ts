import { afterAll, beforeAll, expect, test } from 'vitest';
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import { spawn, type ChildProcess } from 'node:child_process';
import { createServer } from 'node:http';
import { close, listen, startOidcProvider, TEST_CLIENT_SECRET } from './oidc-provider';

let postgres: StartedPostgreSqlContainer;
let postgresStopped = false;
let provider: Awaited<ReturnType<typeof startOidcProvider>>;
let app: ChildProcess;
let base: string;
let appOutput = '';
let ownerIdentity: unknown;

beforeAll(async () => {
  postgres = await new PostgreSqlContainer('postgres:17-alpine').start();
  provider = await startOidcProvider();
  const portServer = createServer();
  const port = await listen(portServer);
  await close(portServer);
  base = `http://127.0.0.1:${port}`;
  app = spawn('node', ['scripts/start.mjs'], { env: { ...process.env, NODE_ENV: 'production', HOST: '127.0.0.1', PORT: String(port), ORIGIN: base, DATABASE_URL: postgres.getConnectionUri() + '?sslmode=disable', OIDC_ISSUER: provider.issuer, OIDC_CLIENT_ID: 'test-client', OIDC_CLIENT_SECRET: TEST_CLIENT_SECRET, OIDC_REDIRECT_URL: base + '/auth/callback', OIDC_ALLOW_INSECURE_HTTP: 'true' }, stdio: ['ignore', 'pipe', 'pipe'] });
  app.stdout!.on('data', data => appOutput += data);
  app.stderr!.on('data', data => appOutput += data);
  for (let attempt = 0; attempt < 100; attempt++) {
    if (app.exitCode !== null) throw new Error(appOutput);
    try { if ((await fetch(base + '/healthz')).ok) return; } catch { /* Wait for migrations and HTTP listener. */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('App did not start: ' + appOutput);
});

afterAll(async () => {
  if (app && app.exitCode === null) {
    const exited = new Promise<void>(resolve => app.once('exit', () => resolve()));
    app.kill('SIGTERM');
    await exited;
  }
  if (provider) await close(provider.server);
  if (postgres && !postgresStopped) await postgres.stop();
});

async function login() {
  const start = await fetch(base + '/auth/login', { redirect: 'manual' });
  expect(start.status).toBe(303);
  const transactionCookie = start.headers.getSetCookie()[0].split(';')[0];
  const authorization = await fetch(start.headers.get('location')!, { redirect: 'manual' });
  const callbackUrl = authorization.headers.get('location')!;
  const callback = await fetch(callbackUrl, { redirect: 'manual', headers: { cookie: transactionCookie } });
  return { callback, callbackUrl, transactionCookie, cookie: callback.headers.getSetCookie().find(cookie => cookie.startsWith('fytorium_session='))?.split(';')[0] ?? '' };
}

test('first OIDC login creates the household and its owner', async () => {
  const { callback, cookie } = await login();
  expect(callback.status).toBe(303);
  expect(callback.headers.get('location')).toBe('/');
  const response = await fetch(base + '/api/me', { headers: { cookie } });
  expect(response.status).toBe(200);
  ownerIdentity = await response.json();
  expect(ownerIdentity).toMatchObject({
    household: { id: expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/), name: 'My household' },
    member: { role: 'owner' },
  });
  const home = await fetch(base, { headers: { cookie } });
  expect(await home.text()).toContain('No plants yet');
  expect(callback.headers.getSetCookie().join(';')).toContain('HttpOnly');
  expect(callback.headers.getSetCookie().join(';')).toContain('SameSite=Lax');
});

test('later login by the same OIDC subject reuses the member and household', async () => {
  const { cookie } = await login();
  const response = await fetch(base + '/api/me', { headers: { cookie } });
  expect(await response.json()).toEqual(ownerIdentity);
});

test('an unknown subject cannot create another household or session', async () => {
  provider.setSubject('synthetic-outsider');
  try {
    const { callback, cookie } = await login();
    expect(callback.headers.get('location')).toBe('/auth/not-member');
    expect(cookie).toBe('');
    const refused = await fetch(base + '/auth/not-member');
    expect(refused.status).toBe(403);
    expect(await refused.text()).toContain('Not a member');
    expect((await fetch(base + '/api/me')).status).toBe(401);
  } finally { provider.setSubject('synthetic-owner'); }
});

test('callback transactions cannot be replayed', async () => {
  const { callbackUrl, transactionCookie } = await login();
  const replay = await fetch(callbackUrl, { redirect: 'manual', headers: { cookie: transactionCookie } });
  expect(replay.headers.get('location')).toBe('/auth/failed');
  expect(replay.headers.getSetCookie().some(cookie => cookie.startsWith('fytorium_session='))).toBe(false);
});

test('a callback with the wrong state does not log in', async () => {
  const start = await fetch(base + '/auth/login', { redirect: 'manual' });
  const cookie = start.headers.getSetCookie()[0].split(';')[0];
  const authorize = await fetch(start.headers.get('location')!, { redirect: 'manual' });
  const url = new URL(authorize.headers.get('location')!);
  url.searchParams.set('state', 'wrong-state');
  const callback = await fetch(url, { redirect: 'manual', headers: { cookie } });
  expect(callback.headers.get('location')).toBe('/auth/failed');
  expect((await fetch(base + '/api/me', { headers: { cookie } })).status).toBe(401);
});

test('OIDC failures log the message and error code without provider details or secrets', async () => {
  const start = await fetch(base + '/auth/login', { redirect: 'manual' });
  const transactionCookie = start.headers.getSetCookie()[0].split(';')[0];
  const authorization = new URL(start.headers.get('location')!);
  const callbackUrl = new URL('/auth/callback', base);
  callbackUrl.searchParams.set('state', authorization.searchParams.get('state')!);
  callbackUrl.searchParams.set('error', 'access_denied');
  callbackUrl.searchParams.set('error_description', 'synthetic-private-provider-detail');
  const response = await fetch(callbackUrl, { redirect: 'manual', headers: { cookie: transactionCookie } });
  expect(response.headers.get('location')).toBe('/auth/failed');
  await expect.poll(() => appOutput).toContain("oidcError: 'access_denied'");
  expect(appOutput).toContain('message:');
  expect(appOutput).not.toContain('synthetic-private-provider-detail');
  expect(appOutput).not.toContain(TEST_CLIENT_SECRET);
  expect(appOutput).not.toContain(transactionCookie.split('=')[1]);
});

test('logout revokes the server-side session and rejects cross-origin form posts', async () => {
  const { cookie } = await login();
  const crossOrigin = await fetch(base + '/auth/logout', { method: 'POST', redirect: 'manual', headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/x-www-form-urlencoded' }, body: '' });
  expect(crossOrigin.status).toBe(403);
  expect((await fetch(base + '/api/me', { headers: { cookie } })).status).toBe(200);
  const logout = await fetch(base + '/auth/logout', { method: 'POST', redirect: 'manual', headers: { cookie, origin: base } });
  expect(logout.status).toBe(303);
  expect((await fetch(base + '/api/me', { headers: { cookie } })).status).toBe(401);
});

test('PWA manifest, generated icons and service worker are served', async () => {
  const response = await fetch(base + '/manifest.webmanifest');
  expect(response.status).toBe(200);
  const manifest = await response.json();
  expect(manifest).toMatchObject({ name: 'Fytorium', start_url: '/', display: 'standalone' });
  expect(manifest.icons).toEqual(expect.arrayContaining([
    expect.objectContaining({ src: '/icon-192.png', sizes: '192x192' }),
    expect.objectContaining({ src: '/icon-512.png', sizes: '512x512' }),
    expect.objectContaining({ src: '/icon.svg', sizes: 'any' }),
  ]));
  for (const path of ['/icon-192.png', '/icon-512.png', '/icon.svg', '/service-worker.js']) {
    expect((await fetch(base + path)).status).toBe(200);
  }
  expect(await (await fetch(base)).text()).toContain('/service-worker.js');
});

test('health checks the database and metrics expose default and HTTP observations', async () => {
  expect(await (await fetch(base + '/healthz')).json()).toEqual({ status: 'ok' });
  const response = await fetch(base + '/metrics');
  expect(response.status).toBe(200);
  expect(response.headers.get('content-type')).toContain('text/plain');
  const metrics = await response.text();
  expect(metrics).toContain('process_resident_memory_bytes');
  expect(metrics).toContain('fytorium_http_requests_total');
  expect(metrics).toContain('fytorium_http_request_duration_seconds_bucket');
});

test('production cannot reach or enable the test clock', async () => {
  expect((await fetch(base + '/__test/clock')).status).toBe(404);
  expect((await fetch(base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-01-01T00:00:00Z' }) })).status).toBe(404);
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
  await postgres.stop();
  postgresStopped = true;
  const headers = { cookie: 'fytorium_session=invalid-session' };
  expect((await fetch(base + '/healthz', { headers })).status).toBe(503);
  expect((await fetch(base + '/metrics', { headers })).status).toBe(200);
});

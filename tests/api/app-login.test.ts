import { beforeEach, expect, test } from 'vitest';
import { createHash } from 'node:crypto';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true });
async function clock(action: Record<string, unknown>) {
  const response = await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(action) });
  expect(response.status).toBe(200);
}
beforeEach(async () => {
  await harness.restartApp();
  await clock({ action: 'set', at: '2030-01-01T00:00:00Z' });
});
const use = (access: string, path = '/api/me', method = 'GET') => fetch(harness.base + path, { method, headers: { authorization: 'Bearer ' + access } });
const refresh = (token: string) => harness.token({ grant_type: 'refresh_token', refresh_token: token });

test('app login returns a bound code without a browser session and exchanges it for bearer identity', async () => {
  const owner = await harness.login();
  const browserIdentity = await (await owner.request('/api/me')).json();
  const login = await harness.appLogin();
  expect(login.callback.status).toBe(303);
  expect(login.location.protocol + login.location.pathname).toBe('com.epaflix.fytorium:/oauth2redirect');
  expect(login.location.searchParams.get('state')).toBe(login.state);
  expect(login.code).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(login.callback.headers.getSetCookie().some(value => value.startsWith('fytorium_session=') && !value.includes('Max-Age=0'))).toBe(false);
  const response = await harness.token({ grant_type: 'authorization_code', code: login.code, code_verifier: login.verifier, device_name: 'Synthetic phone' });
  expect(response.status).toBe(200);
  const tokens = await response.json();
  expect(tokens).toMatchObject({ token_type: 'Bearer', expires_in: 3600, device_id: expect.any(String),
    access_token: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/), refresh_token: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/) });
  expect(tokens.access_token).not.toBe(tokens.refresh_token);
  const me = await fetch(harness.base + '/api/me', { headers: { authorization: 'Bearer ' + tokens.access_token } });
  expect(me.status).toBe(200);
  expect(await me.json()).toEqual(browserIdentity);
});

test('Custom Tab start and callback preserve an existing browser session while returning an app code', async () => {
  const owner = await harness.login();
  const identity = await (await owner.request('/api/me')).json();
  const verifier = 'v'.repeat(43);
  const params = new URLSearchParams({ app: 'android', redirect_uri: 'com.epaflix.fytorium:/oauth2redirect',
    code_challenge: createHash('sha256').update(verifier).digest('base64url'), code_challenge_method: 'S256', state: 'browser-session-preserved' });
  const start = await fetch(harness.base + '/auth/login?' + params, { redirect: 'manual', headers: { cookie: owner.cookie } });
  expect(start.status).toBe(303);
  expect(start.headers.getSetCookie().some(value => value.startsWith('fytorium_session='))).toBe(false);
  expect(await (await owner.request('/api/me')).json()).toEqual(identity);
  const transaction = start.headers.getSetCookie().find(value => value.startsWith('fytorium_login='))!.split(';')[0];
  const authorization = await fetch(start.headers.get('location')!, { redirect: 'manual' });
  const callback = await fetch(authorization.headers.get('location')!, { redirect: 'manual', headers: { cookie: owner.cookie + '; ' + transaction } });
  expect(callback.status).toBe(303);
  expect(callback.headers.getSetCookie().some(value => value.startsWith('fytorium_session='))).toBe(false);
  expect(await (await owner.request('/api/me')).json()).toEqual(identity);
  const location = new URL(callback.headers.get('location')!);
  expect(location.searchParams.get('state')).toBe(params.get('state'));
  expect((await harness.token({ grant_type: 'authorization_code', code: location.searchParams.get('code'), code_verifier: verifier, device_name: 'Shared browser phone' })).status).toBe(200);
});

test('refresh rotates both tokens and replay revokes only that device', async () => {
  const device = await harness.appDevice();
  const other = await harness.appDevice();
  const response = await refresh(device.refresh_token);
  expect(response.status).toBe(200);
  const rotated = await response.json();
  expect(rotated.device_id).toBe(device.device_id);
  expect(rotated.access_token).not.toBe(device.access_token);
  expect(rotated.refresh_token).not.toBe(device.refresh_token);
  expect((await use(device.access_token)).status).toBe(401);
  expect((await use(rotated.access_token)).status).toBe(200);
  const replay = await refresh(device.refresh_token);
  expect(replay.status).toBe(400);
  expect(await replay.json()).toEqual({ error: 'invalid_grant' });
  expect((await use(rotated.access_token)).status).toBe(401);
  expect((await refresh(rotated.refresh_token)).status).toBe(400);
  expect((await use(other.access_token)).status).toBe(200);
});

test('access expires at one hour; refresh survives and logout revokes both tokens', async () => {
  const device = await harness.appDevice();
  await clock({ action: 'advance', milliseconds: 3600000 });
  const expired = await use(device.access_token);
  expect(expired.status).toBe(401);
  expect(await expired.json()).toEqual({ error: 'invalid_token' });
  const response = await refresh(device.refresh_token);
  expect(response.status).toBe(200);
  const rotated = await response.json();
  expect((await use(rotated.access_token, '/api/app/logout', 'POST')).status).toBe(204);
  expect((await use(rotated.access_token)).status).toBe(401);
  expect((await refresh(rotated.refresh_token)).status).toBe(400);
});

test('refresh expires at exactly 90 idle days, not a fixed lifetime', async () => {
  const device = await harness.appDevice();
  await clock({ action: 'advance', milliseconds: 89 * 86400000 });
  const response = await refresh(device.refresh_token);
  expect(response.status).toBe(200);
  const rotated = await response.json();
  await clock({ action: 'advance', milliseconds: 89 * 86400000 });
  const again = await refresh(rotated.refresh_token);
  expect(again.status).toBe(200);
  const newest = await again.json();
  await clock({ action: 'advance', milliseconds: 90 * 86400000 });
  const expired = await refresh(newest.refresh_token);
  expect(expired.status).toBe(400);
  expect(await expired.json()).toEqual({ error: 'invalid_grant' });
});

test('successful bearer use extends the 90-day idle window', async () => {
  const device = await harness.appDevice();
  await clock({ action: 'advance', milliseconds: 3500000 });
  expect((await use(device.access_token)).status).toBe(200);
  await clock({ action: 'advance', milliseconds: 90 * 86400000 - 3400000 });
  expect((await refresh(device.refresh_token)).status).toBe(200);
});

test('removal revokes every app device and pending code, even after a fresh invite', async () => {
  const owner = await harness.login();
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'family' })).json();
  const invited = await harness.appLogin('app-family', invite.link.split('/').at(-1));
  const exchange = await harness.token({ grant_type: 'authorization_code', code: invited.code, code_verifier: invited.verifier, device_name: 'Family phone' });
  expect(exchange.status).toBe(200);
  const first = await exchange.json();
  const second = await harness.appDevice('app-family');
  const pending = await harness.appLogin('app-family');
  const browser = await harness.login('app-family');
  const member = (await (await browser.request('/api/me')).json()).member.id;
  expect((await owner.request('/api/household/members/' + member, 'DELETE')).status).toBe(204);
  expect((await browser.request('/api/me')).status).toBe(401);
  for (const device of [first, second]) {
    expect((await use(device.access_token)).status).toBe(401);
    expect((await refresh(device.refresh_token)).status).toBe(400);
  }
  const refused = await harness.appLogin('app-family');
  expect(refused.location.searchParams.get('error')).toBe('not_member');
  const reinvite = await (await owner.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [] })).json();
  const restored = await harness.appLogin('app-family', reinvite.link.split('/').at(-1));
  const newDevice = await harness.token({ grant_type: 'authorization_code', code: restored.code, code_verifier: restored.verifier, device_name: 'Restored phone' });
  expect(newDevice.status).toBe(200);
  expect((await use((await newDevice.json()).access_token)).status).toBe(200);
  for (const device of [first, second]) {
    expect((await use(device.access_token)).status).toBe(401);
    expect((await refresh(device.refresh_token)).status).toBe(400);
  }
  expect((await harness.token({ grant_type: 'authorization_code', code: pending.code, code_verifier: pending.verifier, device_name: 'Old pending login' })).status).toBe(400);
});

import { beforeEach, expect, test } from 'vitest';
import { createHash } from 'node:crypto';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true });
const digest = (value: string) => createHash('sha256').update(value).digest('hex');
const verifier = 'v'.repeat(43);
const params = () => new URLSearchParams({ app: 'android', redirect_uri: 'com.epaflix.fytorium:/oauth2redirect',
  code_challenge: createHash('sha256').update(verifier).digest('base64url'), code_challenge_method: 'S256', state: 'synthetic state & suffix' });
async function clock(milliseconds: number) {
  expect((await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'advance', milliseconds }) })).status).toBe(200);
}
beforeEach(async () => {
  await harness.restartApp();
  expect((await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-01-01T00:00:00Z' }) })).status).toBe(200);
});
async function dump() {
  const result = await harness.postgres.exec(['pg_dump', '-U', harness.postgres.getUsername(), '-d', harness.postgres.getDatabase(), '--data-only']);
  expect(result.exitCode).toBe(0);
  return result.output;
}

test('app start accepts only the exact redirect URI and S256 challenge with app state', async () => {
  for (const [key, value] of [
    ['redirect_uri', 'https://foreign.invalid/callback'], ['redirect_uri', 'com.epaflix.fytorium:/oauth2redirect/'],
    ['redirect_uri', 'com.epaflix.fytorium://oauth2redirect'], ['redirect_uri', 'COM.EPAFLIX.FYTORIUM:/oauth2redirect'],
    ['code_challenge_method', 'plain'], ['code_challenge', 'short'], ['state', ''], ['state', '\0'], ['app', 'ios'],
  ]) {
    const invalid = params(); invalid.set(key, value);
    const response = await fetch(harness.base + '/auth/login?' + invalid, { redirect: 'manual' });
    expect(response.status).toBe(400);
    expect(response.headers.has('location')).toBe(false);
    expect(response.headers.getSetCookie().some(value => value.startsWith('fytorium_login='))).toBe(false);
  }
  const duplicate = params(); duplicate.append('redirect_uri', 'https://foreign.invalid');
  expect((await fetch(harness.base + '/auth/login?' + duplicate, { redirect: 'manual' })).status).toBe(400);
});

test('wrong verifier and unknown or malformed grants share the same error; a valid code is single use', async () => {
  await harness.login();
  const login = await harness.appLogin();
  const valid = { grant_type: 'authorization_code', code: login.code, code_verifier: login.verifier, device_name: 'Synthetic phone' };
  for (const input of [
    { ...valid, code_verifier: 'x'.repeat(43) }, { ...valid, code: 'z'.repeat(43) },
    { ...valid, code_verifier: 'short' }, { ...valid, device_name: '' }, { ...valid, device_name: '\0' }, { ...valid, device_name: 'x'.repeat(101) },
    { grant_type: 'unknown' }, { grant_type: 'refresh_token', refresh_token: 'z'.repeat(43) }, null, [],
  ]) {
    const response = await harness.token(input);
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: 'invalid_grant' });
  }
  const malformed = await fetch(harness.base + '/api/app/token', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{' });
  expect(malformed.status).toBe(400);
  expect(await malformed.json()).toEqual({ error: 'invalid_grant' });
  expect((await harness.token(valid)).status).toBe(200);
  const reused = await harness.token(valid);
  expect(reused.status).toBe(400);
  expect(await reused.json()).toEqual({ error: 'invalid_grant' });
});

test('codes work just before 60 seconds and expire at the boundary', async () => {
  await harness.login();
  const first = await harness.appLogin();
  await clock(59999);
  expect((await harness.token({ grant_type: 'authorization_code', code: first.code, code_verifier: first.verifier, device_name: 'Synthetic phone' })).status).toBe(200);
  const expired = await harness.appLogin();
  await clock(60000);
  const response = await harness.token({ grant_type: 'authorization_code', code: expired.code, code_verifier: expired.verifier, device_name: 'Synthetic phone' });
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ error: 'invalid_grant' });
});

test('concurrent exchanges issue one device; simultaneous initial refreshes recover once, then older reuse revokes it', async () => {
  await harness.login();
  const login = await harness.appLogin();
  const input = { grant_type: 'authorization_code', code: login.code, code_verifier: login.verifier, device_name: 'Synthetic phone' };
  const exchanges = await Promise.all([harness.token(input), harness.token(input)]);
  expect(exchanges.map(response => response.status).sort()).toEqual([200, 400]);
  const device = await exchanges.find(response => response.ok)!.json();
  const refresh = { grant_type: 'refresh_token', refresh_token: device.refresh_token };
  const responses = await Promise.all([harness.token(refresh), harness.token(refresh)]);
  expect(responses.map(response => response.status)).toEqual([200, 200]);
  const pairs = await Promise.all(responses.map(response => response.json()));
  const uses = await Promise.all(pairs.map(pair => fetch(harness.base + '/api/me', { headers: { authorization: 'Bearer ' + pair.access_token } })));
  expect(uses.map(response => response.status).sort()).toEqual([200, 401]);
  expect((await harness.token(refresh)).status).toBe(400);
  for (const pair of pairs) {
    const me = await fetch(harness.base + '/api/me', { headers: { authorization: 'Bearer ' + pair.access_token } });
    expect(me.status).toBe(401);
    expect(await me.json()).toEqual({ error: 'invalid_token' });
  }
});

test('app failures redirect only to the registered URI with the bound state and no browser session', async () => {
  await harness.login();
  const outsider = await harness.appLogin('app-outsider');
  expect(outsider.location.searchParams.get('error')).toBe('not_member');
  expect(outsider.location.searchParams.get('state')).toBe(outsider.state);
  for (const failure of ['access_denied', 'wrong_state', 'expired_transaction']) {
    const start = await fetch(harness.base + '/auth/login?' + params(), { redirect: 'manual' });
    const cookie = start.headers.getSetCookie().find(value => value.startsWith('fytorium_login='))!.split(';')[0];
    const authorization = new URL(start.headers.get('location')!);
    let callback: URL;
    if (failure === 'access_denied') {
      callback = new URL('/auth/callback', harness.base);
      callback.searchParams.set('state', authorization.searchParams.get('state')!);
      callback.searchParams.set('error', 'access_denied');
      callback.searchParams.set('error_description', 'synthetic-private-provider-detail');
    } else {
      const response = await fetch(authorization, { redirect: 'manual' });
      callback = new URL(response.headers.get('location')!);
      if (failure === 'wrong_state') callback.searchParams.set('state', 'wrong');
      else await clock(600000);
    }
    const response = await fetch(callback, { redirect: 'manual', headers: { cookie } });
    const location = new URL(response.headers.get('location')!, harness.base);
    expect(location.protocol + location.pathname).toBe('com.epaflix.fytorium:/oauth2redirect');
    expect(location.searchParams.get('state')).toBe(params().get('state'));
    expect(location.searchParams.get('error')).toBe(failure === 'access_denied' ? 'access_denied' : 'server_error');
    expect(response.headers.getSetCookie().some(value => value.startsWith('fytorium_session=') && !value.includes('Max-Age=0'))).toBe(false);
  }
  expect(harness.appOutput).toContain("App OIDC callback failed { code: 'access_denied', name: 'AuthorizationResponseError' }");
  expect(harness.appOutput).toContain("App OIDC callback failed { code: 'server_error', name: 'ClientError' }");
  expect(harness.appOutput).toContain("App OIDC callback failed { code: 'server_error', name: 'Error' }");
  expect(harness.appOutput).not.toContain(params().get('state')!);
  expect(harness.appOutput).not.toContain('com.epaflix.fytorium:/oauth2redirect');
  expect(harness.appOutput).not.toContain('synthetic-private-provider-detail');
});

test('database backups store code/token hashes; logs and metric labels contain neither codes nor tokens', async () => {
  await harness.login();
  const pending = await harness.appLogin();
  const backup = await dump();
  expect(backup).not.toContain(pending.code!);
  expect(backup).toContain(digest(pending.code!));
  const device = await harness.appDevice();
  const tokenBackup = await dump();
  for (const token of [device.access_token, device.refresh_token]) {
    expect(tokenBackup).not.toContain(token);
    expect(tokenBackup).toContain(digest(token));
  }
  const metrics = await (await fetch(harness.base + '/metrics')).text();
  for (const sensitive of [pending.code!, device.access_token, device.refresh_token]) {
    expect(harness.appOutput).not.toContain(sensitive);
    expect(metrics).not.toContain(sensitive);
  }
  expect(metrics).toContain('route="/api/app/token",method="POST",status="200"');
});

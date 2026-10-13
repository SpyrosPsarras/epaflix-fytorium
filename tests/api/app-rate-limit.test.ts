import { expect, test } from 'vitest';
import { request } from 'node:http';
import { createApiHarness } from './harness';

const harness = createApiHarness();
function tokenFrom(address: string, input: unknown) {
  return new Promise<Response>((resolve, reject) => {
    const body = JSON.stringify(input);
    const req = request(harness.base + '/api/app/token', { method: 'POST', localAddress: address,
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), 'x-forwarded-for': '192.0.2.1' } }, response => {
      const chunks: Buffer[] = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve(new Response(Buffer.concat(chunks).toString(), { status: response.statusCode, headers: new Headers(response.headers as Record<string, string>) })));
    });
    req.on('error', reject);
    req.end(body);
  });
}

test('code exchange limits known and unknown codes before lookup, without consuming a blocked code', async () => {
  await harness.login();
  const pending = await harness.appLogin();
  const valid = { grant_type: 'authorization_code', code: pending.code, code_verifier: pending.verifier, device_name: 'Synthetic' };
  const invalid = { ...valid, code_verifier: 'x'.repeat(43) };
  for (let i = 0; i < 600; i++) {
    const input = i < 58 && i % 2 ? invalid : { ...invalid, code: 'z'.repeat(43) };
    expect((await harness.token(input)).status).toBe(400);
  }
  for (const input of [valid, { ...valid, code: 'z'.repeat(43) }]) {
    const response = await harness.token(input);
    expect(response.status).toBe(429);
    expect(response.headers.get('retry-after')).toBe('60');
    expect(await response.json()).toEqual({ error: 'rate_limited' });
  }
  expect((await tokenFrom('127.0.0.2', valid)).status).toBe(200);
});

test('refresh device limit follows rotations and IPs without blocking another device', async () => {
  await harness.restartApp();
  let device = await harness.appDevice();
  const other = await harness.appDevice();
  for (let i = 0; i < 10; i++) {
    const response = await tokenFrom(i % 2 ? '127.0.0.2' : '127.0.0.3', { grant_type: 'refresh_token', refresh_token: device.refresh_token });
    expect(response.status).toBe(200);
    device = await response.json();
  }
  const blocked = await harness.token({ grant_type: 'refresh_token', refresh_token: device.refresh_token });
  expect(blocked.status).toBe(429);
  expect(blocked.headers.get('retry-after')).toBe('60');
  expect((await harness.token({ grant_type: 'refresh_token', refresh_token: other.refresh_token })).status).toBe(200);
  await harness.restartApp();
  expect((await harness.token({ grant_type: 'refresh_token', refresh_token: device.refresh_token })).status).toBe(200);
});

test('40 revoked-device refresh attempts do not consume the live device member bucket', async () => {
  await harness.restartApp();
  const revoked = await harness.appDevice();
  const live = await harness.appDevice();
  expect((await fetch(harness.base + '/api/app/logout', { method: 'POST',
    headers: { authorization: `Bearer ${revoked.access_token}` } })).status).toBe(204);
  for (let i = 0; i < 40; i++) {
    expect((await harness.token({ grant_type: 'refresh_token', refresh_token: revoked.refresh_token })).status).toBe(i < 10 ? 400 : 429);
  }
  expect((await harness.token({ grant_type: 'refresh_token', refresh_token: live.refresh_token })).status).toBe(200);
});

test('reuse revocation does not consume a member attempt', async () => {
  await harness.restartApp();
  const reused = await harness.appDevice();
  const live = await harness.appDevice();
  const rotated = await harness.token({ grant_type: 'refresh_token', refresh_token: reused.refresh_token });
  expect(rotated.status).toBe(200);
  const successor = await rotated.json();
  expect((await fetch(harness.base + '/api/plants', {
    headers: { authorization: `Bearer ${successor.access_token}` } })).status).toBe(200);
  const pending = await harness.appLogin();
  await harness.restartApp();
  expect((await harness.token({ grant_type: 'refresh_token', refresh_token: reused.refresh_token })).status).toBe(400);
  for (let i = 0; i < 29; i++) {
    expect((await harness.token({ grant_type: 'authorization_code', code: pending.code,
      code_verifier: 'x'.repeat(43), device_name: 'Synthetic' })).status).toBe(400);
  }
  expect((await harness.token({ grant_type: 'refresh_token', refresh_token: live.refresh_token })).status).toBe(200);
  expect((await fetch(harness.base + '/api/plants', {
    headers: { authorization: `Bearer ${successor.access_token}` } })).status).toBe(401);
});

test('expired codes do not consume the member bucket', async () => {
  await harness.restartApp();
  const live = await harness.appDevice();
  const pending = await harness.appLogin();
  const result = await harness.postgres.exec(['psql', '-U', 'test', '-d', 'test', '-c',
    "UPDATE app_login_codes SET expires_at = now() - interval '1 minute'"]);
  expect(result.exitCode).toBe(0);
  for (let i = 0; i < 40; i++) {
    expect((await harness.token({ grant_type: 'authorization_code', code: pending.code,
      code_verifier: pending.verifier, device_name: 'Synthetic' })).status).toBe(400);
  }
  expect((await harness.token({ grant_type: 'refresh_token', refresh_token: live.refresh_token })).status).toBe(200);
});

test('two devices share the member bucket with code exchanges; another member is unaffected', async () => {
  await harness.restartApp();
  const owner = await harness.login();
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'family' })).json();
  await harness.appLogin('rate-limit-family', invite.link.split('/').at(-1));
  const otherMember = await harness.appDevice('rate-limit-family');
  let first = await harness.appDevice();
  let second = await harness.appDevice();
  const pending = await harness.appLogin();
  const exchange = { grant_type: 'authorization_code', code: pending.code, code_verifier: pending.verifier, device_name: 'Synthetic' };
  // Two successful exchanges plus nine known-code failures use eleven member attempts.
  for (let i = 0; i < 9; i++) {
    expect((await harness.token({ ...exchange, code_verifier: 'x'.repeat(43) })).status).toBe(400);
  }
  for (let i = 0; i < 10; i++) {
    const response = await tokenFrom('127.0.0.2', { grant_type: 'refresh_token', refresh_token: first.refresh_token });
    expect(response.status).toBe(200);
    first = await response.json();
  }
  for (let i = 0; i < 9; i++) {
    const response = await tokenFrom('127.0.0.3', { grant_type: 'refresh_token', refresh_token: second.refresh_token });
    expect(response.status).toBe(200);
    second = await response.json();
  }
  // The tenth refresh on this device is below its own limit but exceeds the member's 30.
  const blocked = await tokenFrom('127.0.0.4', { grant_type: 'refresh_token', refresh_token: second.refresh_token });
  expect(blocked.status).toBe(429);
  expect(blocked.headers.get('retry-after')).toBe('60');
  expect(await blocked.json()).toEqual({ error: 'rate_limited' });
  expect((await harness.token(exchange)).status).toBe(429);
  expect((await harness.token({ grant_type: 'refresh_token', refresh_token: otherMember.refresh_token })).status).toBe(200);
  await harness.restartApp();
  expect((await harness.token(exchange)).status).toBe(200);
  expect((await harness.token({ grant_type: 'refresh_token', refresh_token: second.refresh_token })).status).toBe(200);
});

test('coarse IP abuse guard allows 600 attempts including unknown refreshes and malformed JSON; XFF is not trusted', async () => {
  await harness.restartApp();
  for (let i = 0; i < 600; i++) {
    const response = await fetch(harness.base + '/api/app/token', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: i % 2 ? '{' : JSON.stringify({ grant_type: 'refresh_token', refresh_token: 'z'.repeat(43) }) });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: 'invalid_grant' });
  }
  const blocked = await fetch(harness.base + '/api/app/token', { method: 'POST', headers: { 'content-type': 'application/json', 'x-forwarded-for': '192.0.2.99' }, body: '{' });
  expect(blocked.status).toBe(429);
  expect(blocked.headers.get('retry-after')).toBe('60');
  expect((await tokenFrom('127.0.0.5', { grant_type: 'refresh_token', refresh_token: 'z'.repeat(43) })).status).toBe(400);
});

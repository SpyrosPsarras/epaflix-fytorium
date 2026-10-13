import { beforeEach, expect, test } from 'vitest';
import { createHash } from 'node:crypto';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true });
const digest = (value: string) => createHash('sha256').update(value).digest('hex');
async function clock(action: Record<string, unknown>) {
  expect((await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(action) })).status).toBe(200);
}
async function sql(query: string) {
  const result = await harness.postgres.exec(['psql', '-U', harness.postgres.getUsername(), '-d', harness.postgres.getDatabase(), '-Atc', query]);
  expect(result.exitCode).toBe(0);
  return result.output.trim();
}
const use = (access: string) => fetch(harness.base + '/api/me', { headers: { authorization: 'Bearer ' + access } });
const refresh = (token: string) => harness.token({ grant_type: 'refresh_token', refresh_token: token });
beforeEach(async () => {
  await harness.restartApp();
  await clock({ action: 'set', at: '2030-01-01T00:00:00Z' });
});

test('bearer and refresh requests write device activity only when the stored value is more than one hour old', async () => {
  let device = await harness.appDevice();
  const stored = () => sql(`SELECT last_used_at::text || ':' || xmin::text FROM app_devices WHERE id = '${device.device_id}'`);
  const initial = await stored();
  await clock({ action: 'advance', milliseconds: 1800000 });
  for (let i = 0; i < 3; i++) expect((await use(device.access_token)).status).toBe(200);
  expect(await stored()).toBe(initial);
  await clock({ action: 'advance', milliseconds: 1800000 });
  const response = await refresh(device.refresh_token);
  expect(response.status).toBe(200);
  device = await response.json();
  expect(await stored()).toBe(initial);
  await clock({ action: 'advance', milliseconds: 1 });
  expect((await use(device.access_token)).status).toBe(200);
  const updated = await stored();
  expect(updated).not.toBe(initial);
  expect(updated).toContain('2030-01-01 01:00:00.001');
  expect((await use(device.access_token)).status).toBe(200);
  expect(await stored()).toBe(updated);
});

test('the app maintenance tick persists deferred bearer activity and keeps the exact 90-day idle boundary', async () => {
  const owner = await harness.login();
  const device = await harness.appDevice();
  await clock({ action: 'advance', milliseconds: 3500000 });
  expect((await use(device.access_token)).status).toBe(200);
  await clock({ action: 'advance', milliseconds: 100001 });
  expect((await owner.request('/__test/app-maintenance', 'POST', {})).status).toBe(200);
  expect(await sql(`SELECT last_used_at::text FROM app_devices WHERE id = '${device.device_id}'`)).toContain('2030-01-01 00:58:20');
  await harness.restartApp();
  await clock({ action: 'set', at: new Date(Date.parse('2030-01-01T00:58:20Z') + 90 * 86400000).toISOString() });
  expect((await refresh(device.refresh_token)).status).toBe(400);
});

test('maintenance prunes used refresh hashes and expired access tokens strictly older than 90 days, not current refresh hashes', async () => {
  const device = await harness.appDevice();
  const first = await refresh(device.refresh_token);
  expect(first.status).toBe(200);
  const rotated = await first.json();
  await clock({ action: 'advance', milliseconds: 1 });
  const second = await refresh(rotated.refresh_token);
  expect(second.status).toBe(200);
  const current = await second.json();
  await sql(`INSERT INTO app_access_tokens (token_hash, device_id, expires_at) VALUES
    ('${'1'.repeat(64)}', '${device.device_id}', '2030-01-01T00:00:00Z'),
    ('${'2'.repeat(64)}', '${device.device_id}', '2030-01-01T00:00:00.001Z')`);
  await clock({ action: 'advance', milliseconds: 90 * 86400000 });
  const owner = await harness.login();
  expect((await owner.request('/__test/app-maintenance', 'POST', {})).status).toBe(200);
  const count = (table: string, hash: string) => sql(`SELECT count(*) FROM ${table} WHERE token_hash = '${hash}'`);
  expect(await count('app_refresh_tokens', digest(device.refresh_token))).toBe('0');
  expect(await count('app_refresh_tokens', digest(rotated.refresh_token))).toBe('1');
  expect(await count('app_refresh_tokens', digest(current.refresh_token))).toBe('1');
  expect(await count('app_access_tokens', '1'.repeat(64))).toBe('0');
  expect(await count('app_access_tokens', '2'.repeat(64))).toBe('1');
  expect((await refresh(device.refresh_token)).status).toBe(400);
});

test('manual app maintenance requires membership and rejects a foreign origin', async () => {
  expect((await fetch(harness.base + '/__test/app-maintenance', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })).status).toBe(401);
  const owner = await harness.login();
  expect((await fetch(harness.base + '/__test/app-maintenance', { method: 'POST', headers: { cookie: owner.cookie, origin: 'https://foreign.invalid', 'content-type': 'application/json' }, body: '{}' })).status).toBe(403);
});

test('automatic app maintenance runs with the care worker disabled', async () => {
  const device = await harness.appDevice();
  const hash = '3'.repeat(64);
  await sql(`INSERT INTO app_access_tokens (token_hash, device_id, expires_at) VALUES ('${hash}', '${device.device_id}', '2029-01-01T00:00:00Z')`);
  const count = () => sql(`SELECT count(*) FROM app_access_tokens WHERE token_hash = '${hash}'`);
  expect(await count()).toBe('1');
  await expect.poll(count, { timeout: 65000, interval: 1000 }).toBe('0');
}, 70000);

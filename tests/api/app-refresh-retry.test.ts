import { beforeEach, expect, test } from 'vitest';
import { createHash } from 'node:crypto';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true });
const digest = (value: string) => createHash('sha256').update(value).digest('hex');
const use = (access: string) => fetch(harness.base + '/api/me', { headers: { authorization: 'Bearer ' + access } });
const refresh = (token: string) => harness.token({ grant_type: 'refresh_token', refresh_token: token });
async function clock(action: Record<string, unknown>) {
  expect((await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(action) })).status).toBe(200);
}
async function sql(query: string) {
  const result = await harness.postgres.exec(['psql', '-U', harness.postgres.getUsername(), '-d', harness.postgres.getDatabase(), '-Atc', query]);
  expect(result.exitCode).toBe(0);
  return result.output.trim();
}
async function rotate(token: string) {
  const response = await refresh(token);
  expect(response.status).toBe(200);
  return await response.json() as Awaited<ReturnType<typeof harness.appDevice>>;
}
async function expectRevoked(token: string, current: Awaited<ReturnType<typeof harness.appDevice>>) {
  const response = await refresh(token);
  expect(response.status).toBe(400);
  expect(await response.json()).toEqual({ error: 'invalid_grant' });
  expect(await sql(`SELECT revoked_at IS NOT NULL FROM app_devices WHERE id = '${current.device_id}'`)).toBe('t');
  expect((await use(current.access_token)).status).toBe(401);
  expect((await refresh(current.refresh_token)).status).toBe(400);
}
beforeEach(async () => {
  await harness.restartApp();
  await clock({ action: 'set', at: '2030-01-01T00:00:00Z' });
});

test.each([119000, 120000])('lost reply retry at %i ms issues a fresh pair and revokes the unused successor', async milliseconds => {
  const device = await harness.appDevice();
  const successor = await rotate(device.refresh_token);
  await clock({ action: 'advance', milliseconds });
  const recovered = await rotate(device.refresh_token);
  expect(recovered.device_id).toBe(device.device_id);
  expect(recovered.access_token).not.toBe(successor.access_token);
  expect(recovered.refresh_token).not.toBe(successor.refresh_token);
  expect((await use(successor.access_token)).status).toBe(401);
  expect(await sql(`SELECT used_at IS NOT NULL FROM app_refresh_tokens WHERE token_hash = '${digest(successor.refresh_token)}'`)).toBe('t');
  expect((await use(recovered.access_token)).status).toBe(200);
  await expectRevoked(successor.refresh_token, recovered);
});

test('the discarded successor cannot recover again even while the replacement pair is unused', async () => {
  const device = await harness.appDevice();
  const successor = await rotate(device.refresh_token);
  const recovered = await rotate(device.refresh_token);
  await expectRevoked(successor.refresh_token, recovered);
});

test.each([false, true])('retry after two minutes and one second from the original rotation revokes the device, recent grace: %s', async recentGrace => {
  const device = await harness.appDevice();
  let successor = await rotate(device.refresh_token);
  if (recentGrace) {
    await clock({ action: 'advance', milliseconds: 91000 });
    successor = await rotate(device.refresh_token);
    await clock({ action: 'advance', milliseconds: 30000 });
  } else {
    await clock({ action: 'advance', milliseconds: 121000 });
  }
  await expectRevoked(device.refresh_token, successor);
});

test('two sequential retries replace unused pairs and the last pair supports access and normal refresh', async () => {
  const device = await harness.appDevice();
  const successor = await rotate(device.refresh_token);
  const first = await rotate(device.refresh_token);
  const second = await rotate(device.refresh_token);
  expect(new Set([successor.refresh_token, first.refresh_token, second.refresh_token]).size).toBe(3);
  for (const discarded of [successor, first]) {
    expect((await use(discarded.access_token)).status).toBe(401);
    expect(await sql(`SELECT used_at IS NOT NULL AND first_used_at IS NULL FROM app_refresh_tokens WHERE token_hash = '${digest(discarded.refresh_token)}'`)).toBe('t');
  }
  expect(await sql(`SELECT revoked_at IS NULL FROM app_devices WHERE id = '${device.device_id}'`)).toBe('t');
  expect((await use(second.access_token)).status).toBe(200);
  const current = await rotate(second.refresh_token);
  expect((await use(current.access_token)).status).toBe(200);
});

test('five grace presentations succeed but the sixth revokes the device, including after restart', async () => {
  const device = await harness.appDevice();
  let current = await rotate(device.refresh_token);
  for (let presentation = 1; presentation <= 5; presentation++) {
    const discarded = current;
    current = await rotate(device.refresh_token);
    expect((await use(discarded.access_token)).status).toBe(401);
    expect(await sql(`SELECT used_at IS NOT NULL AND first_used_at IS NULL FROM app_refresh_tokens WHERE token_hash = '${digest(discarded.refresh_token)}'`)).toBe('t');
  }
  await harness.restartApp();
  await clock({ action: 'set', at: '2030-01-01T00:00:01Z' });
  await expectRevoked(device.refresh_token, current);
});

test.each([false, true])('one access use disallows retry within the activity-write window and after restart, grace successor: %s', async graceSuccessor => {
  const device = await harness.appDevice();
  let successor = await rotate(device.refresh_token);
  if (graceSuccessor) successor = await rotate(device.refresh_token);
  await clock({ action: 'advance', milliseconds: 1000 });
  expect((await use(successor.access_token)).status).toBe(200);
  const stored = () => sql(`SELECT first_used_at::text || ':' || xmin::text FROM app_refresh_tokens WHERE token_hash = '${digest(successor.refresh_token)}'`);
  const firstUse = await stored();
  expect(firstUse).toContain('2030-01-01 00:00:01');
  await clock({ action: 'advance', milliseconds: 1000 });
  expect((await use(successor.access_token)).status).toBe(200);
  expect(await stored()).toBe(firstUse);
  expect(await sql(`SELECT last_used_at::text FROM app_devices WHERE id = '${device.device_id}'`)).toContain('2030-01-01 00:00:00');
  await harness.restartApp();
  await clock({ action: 'set', at: '2030-01-01T00:00:03Z' });
  await expectRevoked(device.refresh_token, successor);
});

test.each([false, true])('using the successor refresh token disallows retry of its predecessor, grace successor: %s', async graceSuccessor => {
  const device = await harness.appDevice();
  let successor = await rotate(device.refresh_token);
  if (graceSuccessor) successor = await rotate(device.refresh_token);
  const current = await rotate(successor.refresh_token);
  expect(await sql(`SELECT first_used_at IS NOT NULL FROM app_refresh_tokens WHERE token_hash = '${digest(successor.refresh_token)}'`)).toBe('t');
  await expectRevoked(device.refresh_token, current);
});

test('a token two rotations back revokes the device even if the current pair is unused', async () => {
  const device = await harness.appDevice();
  const successor = await rotate(device.refresh_token);
  const current = await rotate(successor.refresh_token);
  expect(await sql(`SELECT first_used_at IS NULL FROM app_refresh_tokens WHERE token_hash = '${digest(current.refresh_token)}'`)).toBe('t');
  await expectRevoked(device.refresh_token, current);
});

test('two concurrent previous-token retries both succeed and only the last pair remains usable', async () => {
  const device = await harness.appDevice();
  const successor = await rotate(device.refresh_token);
  const results = await Promise.all([refresh(device.refresh_token), refresh(device.refresh_token)]);
  expect(results.map(response => response.status)).toEqual([200, 200]);
  const pairs: Awaited<ReturnType<typeof harness.appDevice>>[] = await Promise.all(results.map(response => response.json()));
  expect(new Set(pairs.map(pair => pair.refresh_token)).size).toBe(2);
  expect(pairs.map(pair => pair.device_id)).toEqual([device.device_id, device.device_id]);
  expect((await use(successor.access_token)).status).toBe(401);
  const accessStatuses = await Promise.all(pairs.map(async pair => (await use(pair.access_token)).status));
  expect([...accessStatuses].sort()).toEqual([200, 401]);
  expect(await sql(`SELECT revoked_at IS NULL FROM app_devices WHERE id = '${device.device_id}'`)).toBe('t');
  const discarded = pairs[accessStatuses.indexOf(401)];
  expect(await sql(`SELECT used_at IS NOT NULL AND first_used_at IS NULL FROM app_refresh_tokens WHERE token_hash = '${digest(discarded.refresh_token)}'`)).toBe('t');
  const current = await rotate(pairs[accessStatuses.indexOf(200)].refresh_token);
  expect((await use(current.access_token)).status).toBe(200);
});

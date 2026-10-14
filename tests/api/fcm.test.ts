import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true });
let browser: Awaited<ReturnType<typeof harness.login>>;
let access: string;
const use = (path: string, method = 'GET', body?: unknown) => fetch(harness.base + path, {
  method, headers: { authorization: 'Bearer ' + access, 'content-type': 'application/json' },
  body: body === undefined ? undefined : JSON.stringify(body),
});
const register = (token: string) => use('/api/notifications/subscriptions', 'POST', { transport: 'fcm', token });
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T07:00:00Z' }) });
  browser = await harness.login(); access = (await harness.appDevice()).access_token;
});

test('FCM registration requires a bearer-bound device, rotates in place and lists both transports without tokens', async () => {
  expect((await browser.request('/api/notifications/subscriptions', 'POST', { transport: 'fcm', token: 'synthetic-denied' })).status).toBe(403);
  expect((await register('')).status).toBe(422);
  const registered = await register('synthetic-phone');
  expect(registered.status).toBe(201);
  const device = await registered.json();
  expect(device).toMatchObject({ transport: 'fcm', enabled: true });
  expect(JSON.stringify(device)).not.toContain('synthetic-phone');
  expect((await (await register('synthetic-rotated')).json()).id).toBe(device.id);
  await browser.request('/api/notifications/subscriptions', 'POST', harness.push.subscribe());
  expect((await (await use('/api/notifications/subscriptions')).json()).map((row: { transport: string }) => row.transport).sort()).toEqual(['fcm', 'web']);
  expect(await (await use('/api/notifications/test', 'POST')).json()).toEqual({ sent: 2 });
  expect(harness.push.fcm.received).toEqual([{ message: { token: 'synthetic-rotated', data: { title: 'Fytorium', body: 'Notifications work on this device.', tag: 'fytorium-test', url: '/' } } }]);
  expect(harness.push.fcm.oauthCalls).toBe(1);
  expect((await use('/api/notifications/subscriptions/' + device.id, 'PATCH', { enabled: false })).status).toBe(200);
  expect(await (await use('/api/notifications/test', 'POST')).json()).toEqual({ sent: 1 });
  expect(harness.push.fcm.received).toHaveLength(1);
  // Token refresh must not silently re-enable a device switched off by its owner.
  expect((await (await register('synthetic-rotated-again')).json()).enabled).toBe(false);
  await use('/api/notifications/subscriptions/' + device.id, 'PATCH', { enabled: true });
});

test('FCM invalid tokens are removed, transient responses retained, and OAuth is cached', async () => {
  for (const [status, code, removed] of [[404, 'UNREGISTERED', true], [400, 'INVALID_ARGUMENT', true], [400, 'BAD_REQUEST', false], [429, 'QUOTA_EXCEEDED', false], [503, 'UNAVAILABLE', false]] as const) {
    const token = 'synthetic-status-' + status + code;
    harness.push.fcm.respond(token, status, code);
    const row = await (await register(token)).json();
    await use('/api/notifications/test', 'POST');
    const rows = await (await use('/api/notifications/subscriptions')).json();
    expect(rows.some((item: { id: string }) => item.id === row.id)).toBe(!removed);
  }
  expect(harness.push.fcm.oauthCalls).toBe(1);
  expect(harness.appOutput).not.toContain('synthetic-status-');
});

test('FCM daily reminders retry 429 and 5xx only after the shared lease and success never repeats', async () => {
  const rows = await (await use('/api/notifications/subscriptions')).json();
  for (const row of rows) await use('/api/notifications/subscriptions/' + row.id, 'DELETE');
  const token = 'synthetic-retry';
  await register(token);
  await use('/api/notifications/settings', 'PUT', { reminder_time: '00:00', reminders_enabled: true, weather_alerts_enabled: false, completion_notices_enabled: false });
  const site = await (await browser.request('/api/sites', 'POST', { name: 'FCM room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await browser.request('/api/species/search?q=Testoria%20lucida')).json();
  expect((await browser.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname: 'FCM fern' })).status).toBe(201);
  const tick = () => browser.request('/__test/push-worker', 'POST');
  const clock = (at: string) => browser.request('/__test/clock', 'POST', { action: 'set', at });
  harness.push.fcm.respond(token, 429, 'QUOTA_EXCEEDED');
  const before = harness.push.fcm.attempted.length;
  await Promise.all([tick(), tick()]);
  expect(harness.push.fcm.attempted).toHaveLength(before + 1);
  await tick();
  expect(harness.push.fcm.attempted).toHaveLength(before + 1);
  harness.push.fcm.respond(token, 503, 'UNAVAILABLE');
  await clock('2030-06-01T07:06:00Z');
  await tick();
  expect(harness.push.fcm.attempted).toHaveLength(before + 2);
  harness.push.fcm.respond(token, 200, 'OK');
  await clock('2030-06-01T07:17:00Z');
  await Promise.all([tick(), tick()]);
  expect(harness.push.fcm.attempted).toHaveLength(before + 3);
  expect(harness.push.fcm.received.at(-1)?.message.data).toEqual({ title: 'Fytorium', body: '1 task today · 0 late · FCM fern', tag: 'fytorium-daily', url: '/' });
  await clock('2030-06-01T07:30:00Z');
  await tick();
  expect(harness.push.fcm.attempted).toHaveLength(before + 3);
});

test('registrations cannot move between app devices or members, and member removal deletes FCM devices', async () => {
  const current = await (await use('/api/notifications/subscriptions')).json();
  const ownerDevice = current.find((row: { transport: string }) => row.transport === 'fcm');
  const otherDevice = await harness.appDevice();
  const duplicate = await fetch(harness.base + '/api/notifications/subscriptions', { method: 'POST',
    headers: { authorization: 'Bearer ' + otherDevice.access_token, 'content-type': 'application/json' },
    body: JSON.stringify({ transport: 'fcm', token: 'synthetic-retry' }) });
  expect(duplicate.status).toBe(409);
  const invite = await (await browser.request('/api/household/invites', 'POST', { role: 'family' })).json();
  const login = await harness.appLogin('synthetic-fcm-family', invite.link.split('/').at(-1));
  const family = await (await harness.token({ grant_type: 'authorization_code', code: login.code, code_verifier: login.verifier, device_name: 'Family phone' })).json();
  const familyRequest = (path: string, method = 'GET', body?: unknown) => fetch(harness.base + path, { method,
    headers: { authorization: 'Bearer ' + family.access_token, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  expect((await familyRequest('/api/notifications/subscriptions', 'POST', { transport: 'fcm', token: 'synthetic-retry' })).status).toBe(409);
  expect((await familyRequest('/api/notifications/subscriptions/' + ownerDevice.id, 'PATCH', { enabled: false })).status).toBe(404);
  expect((await familyRequest('/api/notifications/subscriptions/' + ownerDevice.id, 'DELETE')).status).toBe(404);
  expect((await familyRequest('/api/notifications/subscriptions', 'POST', { transport: 'fcm', token: 'synthetic-family' })).status).toBe(201);
  const member = (await (await familyRequest('/api/me')).json()).member.id;
  expect((await browser.request('/api/household/members/' + member, 'DELETE')).status).toBe(204);
  expect((await familyRequest('/api/notifications/subscriptions')).status).toBe(401);
  // A removed installation's token is no longer reserved by its revoked device.
  const fresh = await fetch(harness.base + '/api/notifications/subscriptions', { method: 'POST',
    headers: { authorization: 'Bearer ' + otherDevice.access_token, 'content-type': 'application/json' },
    body: JSON.stringify({ transport: 'fcm', token: 'synthetic-family' }) });
  expect(fresh.status).toBe(201);
  await fetch(harness.base + '/api/app/logout', { method: 'POST', headers: { authorization: 'Bearer ' + otherDevice.access_token } });
});

test('FCM is optional and logout removes its registration', async () => {
  await register('synthetic-optional');
  await harness.restartApp({ FCM_SERVICE_ACCOUNT_JSON: '', FCM_SERVICE_ACCOUNT_FILE: '' });
  await use('/api/notifications/test', 'POST');
  await use('/api/notifications/test', 'POST');
  expect(harness.appOutput.split('FCM is not configured; app devices skipped.')).toHaveLength(2);
  expect((await use('/api/app/logout', 'POST')).status).toBe(204);
  const rows = await (await browser.request('/api/notifications/subscriptions')).json();
  expect(rows.every((row: { transport: string }) => row.transport === 'web')).toBe(true);
});

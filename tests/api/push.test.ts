import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T06:59:00Z' }) });
  ({ request } = await harness.login());
});

test('members receive only the configured public VAPID key', async () => {
  const response = await request('/api/notifications/key');
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual({ publicKey: harness.vapidPublicKey });
  expect((await request('/api/notifications/key', 'GET', undefined, '')).status).toBe(401);
});

test('a member can register multiple devices, read labels and remove a device', async () => {
  const first = harness.push.subscribe();
  const second = harness.push.subscribe();
  const saved = await request('/api/notifications/subscriptions', 'POST', { ...first, label: 'Test phone' });
  expect(saved.status).toBe(201);
  const device = await saved.json();
  expect((await request('/api/notifications/subscriptions', 'POST', { ...second, label: 'Test tablet' })).status).toBe(201);
  const devices = await (await request('/api/notifications/subscriptions')).json();
  expect(devices).toHaveLength(2);
  expect(devices).toEqual(expect.arrayContaining([
    expect.objectContaining({ id: device.id, label: 'Test phone', last_success_at: null }), expect.objectContaining({ label: 'Test tablet' }),
  ]));
  expect((await request('/api/notifications/subscriptions/' + device.id, 'DELETE')).status).toBe(204);
  expect(await (await request('/api/notifications/subscriptions')).json()).toEqual([expect.objectContaining({ label: 'Test tablet' })]);
});

test('invalid keys and non-public endpoints cannot be registered', async () => {
  const good = harness.push.subscribe();
  for (const endpoint of ['http://push.invalid/send/test', 'https://127.0.0.1/test', 'https://10.0.0.1/test', 'https://169.254.169.254/test', 'https://192.168.1.1/test', 'https://[::1]/test', 'https://[fe80::1]/test', 'https://[::ffff:127.0.0.1]/test', 'https://localhost/test', 'https://user:pass@push.invalid/test']) {
    expect((await request('/api/notifications/subscriptions', 'POST', { ...good, endpoint })).status, endpoint).toBe(422);
  }
  for (const keys of [{}, { ...good.keys, auth: 'bad' }, { ...good.keys, p256dh: 'bad' }, { ...good.keys, p256dh: Buffer.alloc(65).toString('base64url') }]) {
    expect((await request('/api/notifications/subscriptions', 'POST', { ...good, keys })).status).toBe(422);
  }
  for (const body of [null, [], 'invalid']) expect((await request('/api/notifications/subscriptions', 'POST', body)).status).toBe(400);
});

test('the test notification arrives encrypted, decrypts and records device success', async () => {
  const response = await request('/api/notifications/test', 'POST');
  expect(response.status).toBe(200);
  expect(harness.push.received).toEqual([expect.objectContaining({ payload: { title: 'Fytorium', body: 'Notifications work on this device.', tag: 'fytorium-test', url: '/' } })]);
  expect(await (await request('/api/notifications/subscriptions')).json()).toEqual([expect.objectContaining({ last_success_at: expect.any(String) })]);
});

test('each member saves a valid local reminder time and independent switches', async () => {
  expect(await (await request('/api/notifications/settings')).json()).toEqual({ reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled: false });
  for (const body of [{ reminder_time: '24:00', reminders_enabled: true, weather_alerts_enabled: false }, { reminder_time: '9:00', reminders_enabled: true, weather_alerts_enabled: false }, { reminder_time: '09:00', reminders_enabled: 'yes', weather_alerts_enabled: false }]) {
    expect((await request('/api/notifications/settings', 'PUT', body)).status).toBe(422);
  }
  expect((await request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: true, weather_alerts_enabled: false })).status).toBe(200);
  expect(await (await request('/api/notifications/settings')).json()).toEqual({ reminder_time: '09:00', reminders_enabled: true, weather_alerts_enabled: false });
});

test('expired devices are removed on 404 and 410, while temporary failures retain the device', async () => {
  for (const status of [404, 410, 503]) {
    const sub = harness.push.subscribe();
    harness.push.respond(sub.endpoint, status);
    await request('/api/notifications/subscriptions', 'POST', { ...sub, label: 'Status ' + status });
  }
  expect((await request('/api/notifications/test', 'POST')).status).toBe(200);
  const devices = await (await request('/api/notifications/subscriptions')).json();
  expect(devices.map((device: { label: string }) => device.label).sort()).toEqual(['Status 503', 'Test tablet']);
  await request('/api/notifications/subscriptions/' + devices.find((device: { label: string }) => device.label === 'Status 503').id, 'DELETE');
});

test('a stalled push receiver has a total deadline and does not stop other devices', async () => {
  const slow = harness.push.subscribe();
  harness.push.respond(slow.endpoint, 201, 10000);
  const device = await (await request('/api/notifications/subscriptions', 'POST', { ...slow, label: 'Slow phone' })).json();
  const start = Date.now();
  const response = await request('/api/notifications/test', 'POST');
  expect(await response.json()).toEqual({ sent: 1 });
  expect(Date.now() - start).toBeLessThan(8000);
  expect((await (await request('/api/notifications/subscriptions')).json()).find((row: { id: string }) => row.id === device.id)).toMatchObject({ last_success_at: null });
  await request('/api/notifications/subscriptions/' + device.id, 'DELETE');
});

test('push response bodies are discarded without buffering or waiting for their end', async () => {
  const large = harness.push.subscribe();
  harness.push.respond(large.endpoint, 201, 10000, 64 * 1024);
  const device = await (await request('/api/notifications/subscriptions', 'POST', { ...large, label: 'Large response phone' })).json();
  const start = Date.now();
  expect(await (await request('/api/notifications/test', 'POST')).json()).toEqual({ sent: 2 });
  expect(Date.now() - start).toBeLessThan(3000);
  await request('/api/notifications/subscriptions/' + device.id, 'DELETE');
});

let fixtureDb: pg.Pool | undefined;
afterAll(async () => { await fixtureDb?.end(); });
test('notification reads and writes refuse unauthenticated and wrong-origin access and isolate households', async () => {
  const login = await harness.login();
  const device = (await (await request('/api/notifications/subscriptions')).json())[0];
  const endpoints = [
    ['/api/notifications/key', 'GET', undefined], ['/api/notifications/settings', 'GET', undefined],
    ['/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled: false }],
    ['/api/notifications/subscriptions', 'GET', undefined], ['/api/notifications/subscriptions', 'POST', harness.push.subscribe()],
    ['/api/notifications/subscriptions/' + device.id, 'DELETE', undefined], ['/api/notifications/test', 'POST', undefined], ['/__test/push-worker', 'POST', undefined],
  ] as const;
  for (const [path, method, body] of endpoints) {
    expect((await request(path, method, body, '')).status).toBe(401);
    if (method !== 'GET') expect((await fetch(harness.base + path, { method, headers: { cookie: login.cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })).status).toBe(403);
  }
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  // Membership has no invitation interface yet; only membership setup uses SQL.
  await fixtureDb.query('DROP INDEX households_singleton');
  const household = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test push household') RETURNING id");
  await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-push-other', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  const other = await harness.login('synthetic-push-other');
  expect(await (await other.request('/api/notifications/subscriptions')).json()).toEqual([]);
  expect((await other.request('/api/notifications/subscriptions/' + device.id, 'DELETE')).status).toBe(404);
  const subscription = harness.push.subscribe();
  expect((await request('/api/notifications/subscriptions', 'POST', subscription)).status).toBe(201);
  expect((await other.request('/api/notifications/subscriptions', 'POST', subscription)).status).toBe(409);
  expect(await (await other.request('/api/notifications/settings')).json()).toEqual({ reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled: false });
  const before = harness.push.received.length;
  expect(await (await other.request('/api/notifications/test', 'POST')).json()).toEqual({ sent: 0 });
  expect(harness.push.received).toHaveLength(before);
  expect((await (await request('/api/notifications/settings')).json()).reminders_enabled).toBe(true);
});

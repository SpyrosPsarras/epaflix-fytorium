import { beforeAll, expect, test } from 'vitest';
import { randomUUID } from 'node:crypto';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
const harness = createApiHarness({ testClock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let plantId: string;
const clock = (at: string) => request('/__test/clock', 'POST', { action: 'set', at });
const tick = () => request('/__test/push-worker', 'POST');
const settings = (reminders_enabled: boolean) => request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled, weather_alerts_enabled: false });
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T06:59:00Z' }) });
  ({ request } = await harness.login());
  for (const label of ['Phone', 'Tablet']) await request('/api/notifications/subscriptions', 'POST', { ...harness.push.subscribe(), label });
  await settings(true);
  const site = await (await request('/api/sites', 'POST', { name: 'Reminder room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const added = await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname: 'Reminder fern' });
  expect(added.status).toBe(201);
  plantId = (await added.json()).id;
});

test('local reminder time sends a decrypted Today summary to all devices exactly once, including after restart', async () => {
  expect((await tick()).status).toBe(200);
  expect(harness.push.received).toHaveLength(0);
  await clock('2030-06-01T07:00:00Z');
  const today = await (await request('/api/tasks?view=today')).json();
  expect(today.groups.water).toHaveLength(1);
  expect((await tick()).status).toBe(200);
  expect(harness.push.received.map(row => row.payload)).toEqual([
    { title: 'Fytorium', body: '1 task today · 0 late · Reminder fern', tag: 'fytorium-daily', url: '/' },
    { title: 'Fytorium', body: '1 task today · 0 late · Reminder fern', tag: 'fytorium-daily', url: '/' },
  ]);
  await Promise.all([tick(), tick()]);
  expect(harness.push.received).toHaveLength(2);
  await harness.restartApp();
  await clock('2030-06-01T20:00:00Z');
  await tick();
  expect(harness.push.received).toHaveLength(2);
  await clock('2030-06-02T18:00:00Z');
  await Promise.all([tick(), tick()]);
  expect(harness.push.received).toHaveLength(4);
  expect(harness.push.received[2].payload).toEqual({ title: 'Fytorium', body: '1 task today · 1 late · Reminder fern', tag: 'fytorium-daily', url: '/' });
});

test('off skips sends, empty Today skips sends, and graveyard plants never appear', async () => {
  await clock('2030-06-03T07:00:00Z');
  await settings(false);
  await tick();
  expect(harness.push.received).toHaveLength(4);
  await settings(true);
  expect((await request(`/api/plants/${plantId}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).status).toBe(200);
  expect((await request('/api/tasks?view=today')).status).toBe(200);
  await tick();
  expect(harness.push.received).toHaveLength(4);
  await clock('2030-06-04T07:00:00Z');
  expect((await request('/api/plants/' + plantId + '/graveyard', 'POST')).status).toBe(200);
  await clock('2030-07-01T07:00:00Z');
  ({ request } = await harness.login());
  await tick();
  expect(harness.push.received).toHaveLength(4);
});

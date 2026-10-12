import { beforeAll, expect, test } from 'vitest';
import { randomUUID } from 'node:crypto';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
const harness = createApiHarness({ testClock: true });
let owner: Awaited<ReturnType<typeof harness.login>>;
let plantId: string;
const device = harness.push.subscribe();
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T07:00:00Z' }) });
  owner = await harness.login();
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Completion room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  plantId = (await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json()).id;
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [plantId] })).json();
  const sitter = await harness.login('completion-retry-sitter', {}, invite.link.split('/').at(-1));
  await sitter.request('/api/notifications/subscriptions', 'POST', device);
  await sitter.request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled: false, completion_notices_enabled: true });
});
test('completion failures double their lease and stop at six attempts across restart', async () => {
  harness.push.respond(device.endpoint, 503);
  const completion = await (await owner.request('/api/plants/' + plantId + '/tasks/water/complete', 'POST', { request_id: randomUUID() })).json();
  const attempts = () => harness.push.attempted.filter(row => row.payload.tag === 'fytorium-completion-' + completion.event.id).length;
  const tick = () => owner.request('/__test/push-worker', 'POST');
  expect((await tick()).status).toBe(200);
  expect(attempts()).toBe(1);
  for (const [index, minutes] of [5, 10, 20, 40, 80].entries()) {
    await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: (minutes - 1) * 60000 });
    await tick();
    expect(attempts()).toBe(index + 1);
    await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 60001 });
    await Promise.all([tick(), tick()]);
    expect(attempts()).toBe(index + 2);
  }
  expect(harness.appOutput).toContain('Completion push gave up after 6 attempts.');
  await harness.restartApp();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T20:00:00Z' });
  harness.push.respond(device.endpoint, 201);
  await tick();
  expect(attempts()).toBe(6);
  expect(harness.push.received).toHaveLength(0);
});

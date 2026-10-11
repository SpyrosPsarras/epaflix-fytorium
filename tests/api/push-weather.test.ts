import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
const dates = ['2030-05-30', '2030-05-31', '2030-06-01', '2030-06-02', '2030-06-03', '2030-06-04', '2030-06-05', '2030-06-06', '2030-06-07'];
let min = 0;
const harness = createApiHarness({ testClock: true, weather: url => url.pathname === '/v1/forecast' ? {
  status: 200, body: { daily: { time: dates, temperature_2m_min: dates.map(() => min), temperature_2m_max: dates.map(() => 20), precipitation_sum: dates.map(() => 0), daylight_duration: dates.map(() => 43200) } },
} : { status: 503, body: {} } });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let fixtureDb: pg.Pool;
const device = harness.push.subscribe();
afterAll(async () => { await fixtureDb?.end(); });
const tick = () => request('/__test/weather-worker', 'POST');
const switches = (weather_alerts_enabled: boolean) => request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled });
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  ({ request } = await harness.login());
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  await request('/api/notifications/subscriptions', 'POST', { ...device, label: 'Weather phone' });
  const site = await (await request('/api/sites', 'POST', { name: 'Weather terrace', location: 'outdoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  expect((await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname: 'Weather fern' })).status).toBe(201);
  await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
});
test('raising a weather event sends once with a per-event tag, but off and resolved events send nothing', async () => {
  await tick();
  expect((await (await request('/api/weather')).json()).alerts).toHaveLength(1);
  expect(harness.push.received).toHaveLength(0);
  min = 20;
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  expect((await (await request('/api/weather')).json()).alerts).toHaveLength(0);
  await switches(true);
  await request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(0);
  min = 0;
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  const event = (await (await request('/api/weather')).json()).alerts[0];
  expect(harness.push.received).toHaveLength(0);
  await request('/__test/push-worker', 'POST');
  expect(harness.push.received.map(row => row.payload)).toEqual([{ title: 'Fytorium weather alert', body: 'Low temperature forecast · Weather fern. Check Today.', tag: 'fytorium-weather-' + event.id, url: '/' }]);
  await Promise.all([tick(), request('/__test/push-worker', 'POST'), request('/__test/push-worker', 'POST')]);
  expect(harness.push.received).toHaveLength(1);
});

async function raiseAnotherEvent() {
  min = 20;
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  min = 0;
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  return (await (await request('/api/weather')).json()).alerts[0];
}

test('weather reconciliation never waits for push, and temporary failure recovers after the lease', async () => {
  harness.push.respond(device.endpoint, 503, 10000);
  const start = Date.now();
  const event = await raiseAnotherEvent();
  expect(Date.now() - start).toBeLessThan(3000);
  expect(harness.push.received).toHaveLength(1);
  harness.push.respond(device.endpoint, 503);
  await request('/__test/push-worker', 'POST');
  harness.push.respond(device.endpoint, 201);
  await request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(1);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 360000 });
  await Promise.all([request('/__test/push-worker', 'POST'), request('/__test/push-worker', 'POST')]);
  expect(harness.push.received).toHaveLength(2);
  expect(harness.push.received[1].payload.tag).toBe('fytorium-weather-' + event.id);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 360000 });
  await request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(2);
});

test('an unsent weather claim recovers after a crash, but resolved unsent events do not replay', async () => {
  const event = await raiseAnotherEvent();
  const at = (await (await request('/__test/clock')).json()).now;
  // Fault fixture: process died after claiming an event, before delivering it.
  await fixtureDb.query(`INSERT INTO weather_alert_sends (event_id, member_id, attempted_at, claimed_until)
    SELECT $1, id, $2::timestamptz, $2::timestamptz + interval '5 minutes' FROM members`, [event.id, at]);
  await request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(2);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 360000 });
  await Promise.all([request('/__test/push-worker', 'POST'), request('/__test/push-worker', 'POST')]);
  expect(harness.push.received).toHaveLength(3);
  expect(harness.push.received[2].payload.tag).toBe('fytorium-weather-' + event.id);
  await request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(3);
  await raiseAnotherEvent();
  harness.push.respond(device.endpoint, 503);
  await request('/__test/push-worker', 'POST');
  min = 20;
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  harness.push.respond(device.endpoint, 201);
  await request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(3);
});

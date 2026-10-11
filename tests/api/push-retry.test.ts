import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let fixtureDb: pg.Pool;
const device = harness.push.subscribe();
const clock = (at: string) => request('/__test/clock', 'POST', { action: 'set', at });
const tick = () => request('/__test/push-worker', 'POST');
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T07:00:00Z' }) });
  ({ request } = await harness.login());
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  await request('/api/notifications/subscriptions', 'POST', device);
  await request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: true, weather_alerts_enabled: false });
  const site = await (await request('/api/sites', 'POST', { name: 'Retry room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  expect((await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname: 'Retry fern' })).status).toBe(201);
});
afterAll(async () => { await fixtureDb?.end(); });

test('temporary failure retries later the same day, not every tick, and success never repeats', async () => {
  harness.push.respond(device.endpoint, 503);
  await tick();
  expect(harness.push.received).toHaveLength(0);
  harness.push.respond(device.endpoint, 201);
  await tick();
  expect(harness.push.received).toHaveLength(0);
  await clock('2030-06-01T07:06:00Z');
  await Promise.all([tick(), tick()]);
  expect(harness.push.received.map(row => row.payload)).toEqual([
    { title: 'Fytorium', body: '1 task today · 0 late · Retry fern', tag: 'fytorium-daily', url: '/' },
  ]);
  await clock('2030-06-01T20:00:00Z');
  await tick();
  expect(harness.push.received).toHaveLength(1);
});

test('a crash after claiming recovers after restart and lease expiry, then delivers exactly once', async () => {
  await clock('2030-06-02T07:00:00Z');
  // Fault fixture: the process died after INSERT, before contacting any push device.
  await fixtureDb.query(`INSERT INTO daily_reminder_sends (member_id, local_date, attempted_at, claimed_until)
    SELECT id, '2030-06-02', '2030-06-02T07:00:00Z', '2030-06-02T07:05:00Z' FROM members`);
  await clock('2030-06-02T07:04:00Z');
  await tick();
  expect(harness.push.received).toHaveLength(1);
  await harness.restartApp();
  await clock('2030-06-02T07:06:00Z');
  await Promise.all([tick(), tick()]);
  expect(harness.push.received).toHaveLength(2);
  expect(harness.push.received[1].payload.body).toBe('1 task today · 1 late · Retry fern');
  await clock('2030-06-02T20:00:00Z');
  await tick();
  expect(harness.push.received).toHaveLength(2);
});

test('a missed day is never replayed on the next local day, which sends only its own summary', async () => {
  await clock('2030-06-03T21:59:00Z');
  harness.push.respond(device.endpoint, 503);
  await tick();
  expect(harness.push.received).toHaveLength(2);
  harness.push.respond(device.endpoint, 201);
  // Oslo changes date at 22:00 UTC, not midnight UTC.
  await clock('2030-06-03T22:06:00Z');
  await tick();
  expect(harness.push.received).toHaveLength(2);
  await clock('2030-06-04T07:00:00Z');
  await Promise.all([tick(), tick()]);
  expect(harness.push.received).toHaveLength(3);
  expect(harness.push.received[2].payload.body).toBe('2 tasks today · 1 late · Retry fern');
});

test('all expired devices finish the day, even if a new device is registered later', async () => {
  await clock('2030-06-05T07:00:00Z');
  harness.push.respond(device.endpoint, 410);
  const otherDevice = harness.push.subscribe();
  harness.push.respond(otherDevice.endpoint, 404);
  await request('/api/notifications/subscriptions', 'POST', otherDevice);
  await tick();
  expect(await (await request('/api/notifications/subscriptions')).json()).toEqual([]);
  expect(harness.push.received).toHaveLength(3);
  harness.push.respond(device.endpoint, 201);
  await request('/api/notifications/subscriptions', 'POST', device);
  await clock('2030-06-05T07:06:00Z');
  await tick();
  expect(harness.push.received).toHaveLength(3);
});

test('one member failing to claim does not block the member after them', async () => {
  await clock('2030-06-06T07:00:00Z');
  // Membership has no invitation API yet. The trigger injects a database failure for the first member.
  await fixtureDb.query(`INSERT INTO members (household_id, issuer, subject, role)
    SELECT household_id, issuer, 'synthetic-retry-other', 'family' FROM members LIMIT 1`);
  const other = await harness.login('synthetic-retry-other');
  await other.request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: true, weather_alerts_enabled: false });
  await other.request('/api/notifications/subscriptions', 'POST', harness.push.subscribe());
  await fixtureDb.query(`CREATE FUNCTION fail_first_push_claim() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN
      IF NEW.member_id = (SELECT id FROM members ORDER BY id LIMIT 1) THEN
        RAISE EXCEPTION 'Synthetic claim failure';
      END IF;
      RETURN NEW;
    END $$;
    CREATE TRIGGER fail_first_push_claim BEFORE INSERT ON daily_reminder_sends
      FOR EACH ROW EXECUTE FUNCTION fail_first_push_claim()`);
  try {
    expect((await tick()).status).toBe(200);
    expect(harness.push.received).toHaveLength(4);
    expect(harness.appOutput).toContain('Daily push failed for one member.');
  } finally {
    await fixtureDb.query('DROP TRIGGER fail_first_push_claim ON daily_reminder_sends; DROP FUNCTION fail_first_push_claim()');
  }
  await tick();
  expect(harness.push.received).toHaveLength(5);
  await tick();
  expect(harness.push.received).toHaveLength(5);
});

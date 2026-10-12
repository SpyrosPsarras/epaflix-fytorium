import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
let minC = 0;
const harness = createApiHarness({ testClock: true, weather: url => url.pathname === '/v1/forecast' ? {
  status: 200, body: { daily: { time: ['2030-06-01'], temperature_2m_min: [minC], temperature_2m_max: [20], precipitation_sum: [0], daylight_duration: [43200] } },
} : { status: 503, body: {} } });
let owner: Awaited<ReturnType<typeof harness.login>>;
let fixtureDb: pg.Pool;
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  owner = await harness.login();
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Test retry terrace', location: 'outdoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  expect((await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).status).toBe(201);
  await owner.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
});
afterAll(async () => { await fixtureDb?.end(); });

test('a failed reconcile of new forecast data retries next tick even with unchanged care inputs', async () => {
  const tick = () => owner.request('/__test/weather-worker', 'POST');
  expect((await tick()).status).toBe(200);
  expect((await (await owner.request('/api/weather')).json()).alerts).toHaveLength(1);
  minC = 20;
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  // Database fault at the HTTP seam, not a mock of reconciliation.
  await fixtureDb.query(`CREATE FUNCTION fail_weather_reconcile() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN RAISE EXCEPTION 'Synthetic reconcile failure'; END $$;
    CREATE TRIGGER fail_weather_reconcile BEFORE UPDATE ON weather_events
    FOR EACH ROW EXECUTE FUNCTION fail_weather_reconcile()`);
  try { expect((await tick()).status).toBe(500); }
  finally { await fixtureDb.query('DROP TRIGGER fail_weather_reconcile ON weather_events; DROP FUNCTION fail_weather_reconcile()'); }
  expect((await tick()).status).toBe(200);
  expect((await (await owner.request('/api/weather')).json()).alerts).toEqual([]);
});

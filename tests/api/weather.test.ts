import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { fixturePlant } from '../plant-fixture';
import { createApiHarness } from './harness';

const dates = ['2030-05-30', '2030-05-31', '2030-06-01', '2030-06-02', '2030-06-03', '2030-06-04', '2030-06-05', '2030-06-06', '2030-06-07'];
let forecastStatus = 200;
let forecastCalls = 0;
let climateCalls = 0;
let climateStatus = 200;
const forecast = { daily: { time: dates, temperature_2m_min: dates.map(() => 10), temperature_2m_max: dates.map(() => 30), precipitation_sum: dates.map(() => 6), daylight_duration: dates.map(() => 43200) } };
const climate = { daily: { time: Array.from({ length: 12 }, (_, i) => `2000-${String(i + 1).padStart(2, '0')}-01`), temperature_2m_min: [-5, -3, 2, 6, 10, 15, 18, 17, 12, 7, 1, -4] } };
const harness = createApiHarness({ testClock: true, weather: url => {
  if (url.pathname === '/v1/forecast') {
    forecastCalls++;
    expect(url.searchParams.get('past_days')).toBe('2');
    expect(url.searchParams.get('forecast_days')).toBe('7');
    expect(url.searchParams.get('daily')).toBe('temperature_2m_min,temperature_2m_max,precipitation_sum,daylight_duration');
    return { status: forecastStatus, body: forecast };
  }
  if (url.pathname === '/v1/climate') { climateCalls++; return { status: climateStatus, body: climate }; }
  return { status: 404, body: {} };
} });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  ({ request } = await harness.login());
});

test('household location validates both coordinates and an optional label, then persists once', async () => {
  expect(await (await request('/api/household/location')).json()).toEqual({ location: null });
  for (const location of [
    { latitude: 91, longitude: .5 }, { latitude: .5, longitude: -181 },
    { latitude: '0.5', longitude: .5 }, { latitude: .5 },
    { latitude: .5, longitude: .5, label: 1 }, { latitude: .5, longitude: .5, label: 'x'.repeat(201) },
  ]) expect((await request('/api/household/location', 'PUT', { location })).status).toBe(422);
  expect((await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5, label: 'Test location' } })).status).toBe(200);
  expect(await (await request('/api/household/location')).json()).toEqual({ location: { latitude: .5, longitude: .5, label: 'Test location' } });
  expect((await request('/api/household/location', 'PUT', { location: null })).status).toBe(200);
  expect(await (await request('/api/household/location')).json()).toEqual({ location: null });
});

const tick = () => request('/__test/weather-worker', 'POST');
const report = () => request('/api/weather').then(response => response.json());

test('background refresh is hourly, keeps last good data on failure, and climate is monthly', async () => {
  await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
  expect((await report()).snapshot).toBeNull();
  expect(forecastCalls).toBe(0);
  const firstTick = await tick();
  expect(firstTick.status).toBe(200);
  expect(await report()).toMatchObject({ forecastFetchedAt: '2030-06-01T12:00:00.000Z', unavailableSince: null, snapshot: { today: '2030-06-01', meanMaxTemperatureC: 30, daylightHours: 12 } });
  await tick();
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3599999 });
  await tick();
  expect(forecastCalls).toBe(1);
  expect(climateCalls).toBe(1);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 1 });
  forecastStatus = 503;
  await tick();
  expect(forecastCalls).toBe(2);
  expect(await report()).toMatchObject({ forecastFetchedAt: '2030-06-01T12:00:00.000Z', unavailableSince: '2030-06-01T13:00:00.000Z', snapshot: { meanMaxTemperatureC: 30 } });
  await tick();
  expect(forecastCalls).toBe(2);
  forecastStatus = 200;
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  expect(await report()).toMatchObject({ forecastFetchedAt: '2030-06-01T14:00:00.000Z', unavailableSince: null });
  expect(climateCalls).toBe(1);
  await request('/__test/clock', 'POST', { action: 'set', at: '2030-07-01T12:00:00Z' });
  ({ request } = await harness.login());
  await tick();
  expect(climateCalls).toBe(2);
  await request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T14:00:00Z' });
});

let outdoorPot: string;
let outdoorGround: string;
let indoor: string;
let graveyard: string;
let customTaskId: string;
async function addPlant(location: 'indoor' | 'outdoor', pot_type: 'plastic' | 'in_ground', nickname: string) {
  const site = await (await request('/api/sites', 'POST', { name: nickname + ' site', location, light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const added = await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, pot_type, nickname });
  expect(added.status).toBe(201);
  return (await added.json()).id as string;
}

test('cached weather changes outdoor tasks only and raises deduped cold-spell alerts using pot and ground hardiness', async () => {
  outdoorPot = await addPlant('outdoor', 'plastic', 'Test weather pot');
  outdoorGround = await addPlant('outdoor', 'in_ground', 'Test weather ground');
  indoor = await addPlant('indoor', 'plastic', 'Test weather indoor');
  graveyard = await addPlant('outdoor', 'plastic', 'Test weather graveyard');
  expect((await request('/api/plants/' + graveyard + '/graveyard', 'POST')).status).toBe(200);
  // Changing location discards data from the previous location and permits a new background fetch.
  await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .6 } });
  forecast.daily.temperature_2m_min = dates.map(() => 8);
  const simultaneous = await Promise.all([tick(), tick()]);
  for (const response of simultaneous) expect(response.status).toBe(200);
  const custom = await request('/api/custom-tasks', 'POST', { plant_id: outdoorPot, name: 'Test inspect after rain', interval_days: 7 });
  expect(custom.status).toBe(201);
  customTaskId = (await custom.json()).id;
  const tasks = async (id: string) => (await request('/api/plants/' + id + '/tasks')).json();
  expect((await tasks(outdoorPot)).customTasks[customTaskId]).toMatchObject({ intervalDays: 7, dueOn: '2030-06-08' });
  for (const view of ['today', 'upcoming']) {
    const list = await (await request('/api/tasks?view=' + view)).json();
    expect(list.groups.water).toContainEqual(expect.objectContaining({ plant_id: outdoorPot, intervalDays: 4,
      basedOn: expect.arrayContaining([expect.objectContaining({ input: 'weather.meanMaxTemperatureC', factor: .75 })]) }));
    if (view === 'upcoming') expect(list.groups[customTaskId]).toContainEqual(expect.objectContaining({ plant_id: outdoorPot, intervalDays: 7, dueOn: '2030-06-08' }));
  }
  expect((await tasks(outdoorPot)).water.intervalDays).toBe(4);
  expect((await tasks(indoor)).water.intervalDays).toBe(7);
  expect((await tasks(outdoorPot)).water.basedOn).toContainEqual(expect.objectContaining({ input: 'weather.meanMaxTemperatureC', value: 30, source: 'weather', factor: .75 }));
  expect((await tasks(indoor)).water.basedOn.filter((row: { source: string; factor?: number }) => row.source === 'weather' && row.factor !== undefined)).toEqual([]);
  const first = await report();
  expect(first.alerts).toHaveLength(1);
  expect(first.alerts[0]).toMatchObject({ plant_id: outdoorPot, starts_on: '2030-06-01', ends_on: '2030-06-07', payload: { thresholdC: 10, minC: 8, hardiness: { value: 5, source: 'rule', ruleId: 'hardiness.default@1' }, potRuleId: 'hardiness.pot_offset@1' } });
  await Promise.all([tick(), tick()]);
  expect((await report()).alerts.map((event: { id: string }) => event.id)).toEqual([first.alerts[0].id]);
  // Effective care override supplies zones, interpreted as USDA with explicit uncertainty.
  expect((await request('/api/species/910001/care/overrides/hardiness', 'PUT', { value: { min: 11, max: 12 } })).status).toBe(200);
  await tick();
  expect((await report()).alerts[0]).toMatchObject({ id: first.alerts[0].id, payload: { thresholdC: 9.4, hardiness: { value: 4.4, source: 'override', ruleId: 'hardiness.usda_uncertain@1' } } });
  forecast.daily.temperature_2m_min = dates.map(() => 0);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  expect((await report()).alerts.map((event: { plant_id: string }) => event.plant_id).sort()).toEqual([outdoorPot, outdoorGround].sort());
  expect((await report()).alerts.find((event: { plant_id: string }) => event.plant_id === outdoorGround).payload.thresholdC).toBe(4.4);
});

test('rain watering is one atomic idempotent batch for active outdoor plants, with one undo', async () => {
  expect((await report()).rain).toMatchObject({ plantCount: 2, precipitationMm: 6, ruleId: 'weather.rain@1' });
  const history = async (id: string) => (await request('/api/plants/' + id + '/history')).json();
  const customBefore = (await (await request('/api/plants/' + outdoorPot + '/tasks')).json()).customTasks[customTaskId];
  const request_id = randomUUID();
  const failureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  // Fail the second plant write to verify the shared action, events and rain link roll back together.
  await failureDb.query(`CREATE FUNCTION fail_test_rain() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.batch_id IS NOT NULL AND NEW.plant_id = GREATEST('` + outdoorPot + `'::uuid, '` + outdoorGround + `'::uuid) THEN RAISE EXCEPTION 'Synthetic rain failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER fail_test_rain BEFORE INSERT ON care_events FOR EACH ROW EXECUTE FUNCTION fail_test_rain()`);
  try {
    expect((await request('/api/weather/rain/water', 'POST', { request_id })).status).toBe(500);
    for (const id of [outdoorPot, outdoorGround]) expect(await history(id)).toEqual([]);
    expect((await report()).rain.batchId).toBeNull();
  } finally {
    await failureDb.query('DROP TRIGGER fail_test_rain ON care_events; DROP FUNCTION fail_test_rain()');
    await failureDb.end();
  }
  const concurrentRequest = randomUUID();
  const responses = await Promise.all([request('/api/weather/rain/water', 'POST', { request_id }), request('/api/weather/rain/water', 'POST', { request_id: concurrentRequest })]);
  for (const response of responses) expect(response.status).toBe(200);
  const batches = await Promise.all(responses.map(response => response.json()));
  expect(batches[0]).toMatchObject({ plantCount: 2, batchId: expect.any(String), undoneAt: null });
  expect(batches[1]).toEqual(batches[0]);
  const secondRequest = randomUUID();
  const secondTap = await request('/api/weather/rain/water', 'POST', { request_id: secondRequest });
  expect(secondTap.status).toBe(200);
  expect(await secondTap.json()).toEqual(batches[0]);
  expect((await report()).rain.batchId).toBe(batches[0].batchId);
  for (const id of [outdoorPot, outdoorGround]) expect(await history(id)).toEqual([expect.objectContaining({ task_type: 'water', custom_task_id: null, kind: 'completed', batch_id: batches[0].batchId, by_name: 'Test member', undone_at: null })]);
  expect(await history(indoor)).toEqual([]);
  expect(await history(graveyard)).toEqual([]);
  expect((await (await request('/api/plants/' + outdoorPot + '/tasks')).json()).customTasks[customTaskId]).toEqual(customBefore);
  expect([request_id, concurrentRequest]).toContain(batches[0].batchId);
  expect((await request('/api/care-events/' + (await history(outdoorPot))[0].id + '/undo', 'POST')).status).toBe(409);
  expect((await request('/api/plants/' + indoor + '/tasks/water/complete', 'POST', { request_id })).status).toBe(409);
  await request('/api/plants/' + outdoorPot + '/graveyard', 'POST');
  expect((await request('/api/task-batches/' + batches[0].batchId + '/undo', 'POST')).status).toBe(409);
  for (const id of [outdoorPot, outdoorGround]) expect((await history(id))[0].undone_at).toBeNull();
  await request('/api/plants/' + outdoorPot + '/restore', 'POST', {});
  expect((await request('/api/task-batches/' + batches[0].batchId + '/undo', 'POST')).status).toBe(200);
  for (const id of [outdoorPot, outdoorGround]) expect((await history(id))[0].undone_at).toBe('2030-06-01T15:00:00.000Z');
  expect((await request('/api/task-batches/' + batches[0].batchId + '/undo', 'POST')).status).toBe(409);
  expect(await (await request('/api/weather/rain/water', 'POST', { request_id })).json()).toMatchObject({ batchId: batches[0].batchId, plantCount: 2, undoneAt: '2030-06-01T15:00:00.000Z' });
  for (const id of [outdoorPot, outdoorGround]) expect(await history(id)).toHaveLength(1);
  expect((await report()).rain.batchId).toBeNull();
  expect(await (await request('/api/weather/rain/water', 'POST', { request_id: secondRequest })).json()).toMatchObject({ batchId: batches[0].batchId, undoneAt: '2030-06-01T15:00:00.000Z' });
  const again = await (await request('/api/weather/rain/water', 'POST', { request_id: randomUUID() })).json();
  expect(again.batchId).not.toBe(batches[0].batchId);
  for (const id of [outdoorPot, outdoorGround]) expect(await history(id)).toHaveLength(2);
});

test('species outdoor months compare monthly mean minima to effective ground and potted hardiness plus margin', async () => {
  const months = () => request('/api/species/910001/outdoor-months').then(response => response.json());
  expect(await months()).toMatchObject({ locationSet: true, ground: [5, 6, 7, 8, 9, 10], pot: [6, 7, 8, 9], groundThresholdC: 4.4, potThresholdC: 9.4, marginC: 2, ruleId: 'hardiness.outdoor_months@1', potRuleId: 'hardiness.pot_offset@1', hardiness: { source: 'override', ruleId: 'hardiness.usda_uncertain@1' } });
  await request('/api/species/910001/care/overrides/hardiness', 'DELETE');
  expect(await months()).toMatchObject({ ground: [5, 6, 7, 8, 9, 10], pot: [6, 7, 8, 9], groundThresholdC: 5, potThresholdC: 10, hardiness: { source: 'rule', ruleId: 'hardiness.default@1' } });
  await request('/api/household/location', 'PUT', { location: null });
  expect(await months()).toMatchObject({ locationSet: false, pot: null, ground: null, fetchedAt: null });
  await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
  await tick();
});

test('climate failure keeps outdoor months, retries after six hours, then waits a month after success', async () => {
  await request('/__test/clock', 'POST', { action: 'set', at: '2030-07-01T15:00:00Z' });
  ({ request } = await harness.login());
  const before = climateCalls;
  climateStatus = 503;
  await tick();
  const months = await (await request('/api/species/910001/outdoor-months')).json();
  expect(months).toMatchObject({ pot: [6, 7, 8, 9], ground: [5, 6, 7, 8, 9, 10], fetchedAt: '2030-06-01T15:00:00.000Z', unavailableSince: '2030-07-01T15:00:00.000Z' });
  await tick();
  expect(climateCalls).toBe(before + 1);
  climateStatus = 200;
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 6 * 3600000 - 1 });
  await tick();
  expect(climateCalls).toBe(before + 1);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 1 });
  await tick();
  expect(climateCalls).toBe(before + 2);
  expect(await (await request('/api/species/910001/outdoor-months')).json()).toMatchObject({ unavailableSince: null, fetchedAt: '2030-07-01T21:00:00.000Z' });
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 6 * 3600000 });
  await tick();
  expect(climateCalls).toBe(before + 2);
  await request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T15:00:00Z' });
  await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .6 } });
  await tick();
});

test('separate cold spells stay distinct and resolve when the forecast warms, while dry days refuse rain watering', async () => {
  forecast.daily.temperature_2m_min = [0, 0, 0, 0, 20, 0, 0, 20, 20];
  forecast.daily.precipitation_sum = dates.map(() => 4.9);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  const alerts = (await report()).alerts.filter((event: { plant_id: string }) => event.plant_id === outdoorPot);
  expect(alerts.map((event: { starts_on: string; ends_on: string }) => [event.starts_on, event.ends_on]).sort()).toEqual([['2030-06-01', '2030-06-02'], ['2030-06-04', '2030-06-05']]);
  await tick();
  expect((await report()).alerts.filter((event: { plant_id: string }) => event.plant_id === outdoorPot)).toHaveLength(2);
  expect((await report()).rain).toBeNull();
  const request_id = randomUUID();
  expect((await request('/api/weather/rain/water', 'POST', { request_id })).status).toBe(409);
  // A refresh that joins two previous spells must reuse one event without violating exclusion.
  forecast.daily.temperature_2m_min = dates.map(() => 0);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  const joined = await Promise.all([tick(), tick()]);
  for (const response of joined) expect(response.status).toBe(200);
  const merged = (await report()).alerts.filter((event: { plant_id: string }) => event.plant_id === outdoorPot);
  expect(merged).toHaveLength(1);
  expect(merged[0]).toMatchObject({ starts_on: '2030-06-01', ends_on: '2030-06-07' });
  expect(alerts.map((event: { id: string }) => event.id)).toContain(merged[0].id);
  forecast.daily.temperature_2m_min = dates.map(() => 20);
  forecast.daily.precipitation_sum = dates.map(() => 6);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 3600000 });
  await tick();
  expect((await report()).alerts).toEqual([]);
  // The refused command left no idempotency record or partial care events.
  expect((await request('/api/weather/rain/water', 'POST', { request_id })).status).toBe(200);
});

let fixtureDb: pg.Pool | undefined;
afterAll(async () => { if (fixtureDb) await fixtureDb.end(); });
test('weather, location, rain and undo require authentication, reject foreign origins and cannot cross households', async () => {
  const { cookie } = await harness.login();
  const batch = (await report()).lastRainBatch;
  const endpoints = [
    ['/api/household/location', 'GET', undefined],
    ['/api/household/location', 'PUT', { location: null }],
    ['/api/weather', 'GET', undefined],
    ['/api/species/910001/outdoor-months', 'GET', undefined],
    ['/api/weather/rain/water', 'POST', { request_id: randomUUID() }],
    ['/api/task-batches/' + batch.id + '/undo', 'POST', undefined],
    ['/__test/weather-worker', 'POST', undefined],
  ] as const;
  for (const [path, method, body] of endpoints) {
    for (const session of ['', 'fytorium_session=invalid']) expect((await request(path, method, body, session)).status).toBe(401);
    if (method !== 'GET') expect((await fetch(harness.base + path, { method, headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })).status).toBe(403);
  }
  for (const path of ['/api/household/location', '/api/weather/rain/water']) {
    for (const body of [null, [], 'bad']) expect((await request(path, path.endsWith('location') ? 'PUT' : 'POST', body)).status).toBe(400);
  }
  for (const request_id of [undefined, 'invalid', 42]) expect((await request('/api/weather/rain/water', 'POST', { request_id })).status).toBe(422);
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  // Membership has no invitation interface on this base. Only membership setup bypasses HTTP.
  await fixtureDb.query('DROP INDEX households_singleton');
  const foreign = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test foreign weather household') RETURNING id");
  await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-weather-other', 'owner' FROM members LIMIT 1", [foreign.rows[0].id]);
  const other = await harness.login('synthetic-weather-other');
  expect(await (await other.request('/api/weather')).json()).toMatchObject({ locationSet: false, alerts: [], rain: null, lastRainBatch: null });
  expect((await other.request('/api/species/910001/outdoor-months')).status).toBe(404);
  expect((await other.request('/api/task-batches/' + batch.id + '/undo', 'POST')).status).toBe(404);
  const original = await (await request('/api/household/location')).json();
  await other.request('/api/household/location', 'PUT', { household_id: foreign.rows[0].id, location: { latitude: .5, longitude: .5, label: 'Test other location' } });
  expect(await (await request('/api/household/location')).json()).toEqual(original);
  const rainRequest = randomUUID();
  const rain = await (await request('/api/weather/rain/water', 'POST', { request_id: rainRequest })).json();
  expect((await other.request('/api/weather/rain/water', 'POST', { request_id: rainRequest })).status).toBe(409);
  expect((await other.request('/api/task-batches/' + rain.batchId + '/undo', 'POST')).status).toBe(404);
  expect(harness.appOutput).not.toMatch(/latitude=|longitude=|latitude:|longitude:/);
});

test('batch integrity rejects foreign households, missing plants and self-referencing aliases in SQL', async () => {
  fixtureDb ??= new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  const batch = await fixtureDb.query("SELECT request_id, household_id FROM care_actions WHERE action = 'rain' AND batch_id IS NULL LIMIT 1");
  const { request_id: batchId, household_id: household } = batch.rows[0];
  const foreign = await fixtureDb.query('SELECT id FROM households WHERE id <> $1 LIMIT 1', [household]);
  const foreignHousehold = foreign.rows[0].id;
  // Integrity tests deliberately bypass HTTP validation through the harness database.
  await expect(fixtureDb.query("INSERT INTO care_actions (request_id, household_id, action, batch_id) VALUES ($1, $2, 'rain', $3)",
    [randomUUID(), foreignHousehold, batchId])).rejects.toMatchObject({ code: '23503', constraint: 'care_actions_batch_household' });
  await expect(fixtureDb.query("INSERT INTO care_actions (request_id, household_id, action) VALUES ($1, $2, 'water:completed')",
    [randomUUID(), household])).rejects.toMatchObject({ code: '23514', constraint: 'care_actions_action_plant' });
  const self = randomUUID();
  await expect(fixtureDb.query("INSERT INTO care_actions (request_id, household_id, action, batch_id) VALUES ($1, $2, 'rain', $1)",
    [self, household])).rejects.toMatchObject({ code: '23514', constraint: 'care_actions_rain_alias' });
  await expect(fixtureDb.query("INSERT INTO weather_events (kind, household_id, starts_on, ends_on, payload, created_at, batch_id) VALUES ('rain', $1, '2040-01-01', '2040-01-01', '{}', now(), $2)",
    [foreignHousehold, batchId])).rejects.toMatchObject({ code: '23503', constraint: 'weather_events_batch_household' });
  const foreignBatch = randomUUID();
  await fixtureDb.query("INSERT INTO care_actions (request_id, household_id, action) VALUES ($1, $2, 'rain')", [foreignBatch, foreignHousehold]);
  try {
    await expect(fixtureDb.query("INSERT INTO care_events (household_id, plant_id, task_type, kind, occurred_at, by_name, request_id, batch_id) VALUES ($1, $2, 'water', 'completed', now(), 'Integrity test', $3, $4)",
      [household, outdoorPot, randomUUID(), foreignBatch])).rejects.toMatchObject({ code: '23503', constraint: 'care_events_batch_household' });
  } finally {
    await fixtureDb.query('DELETE FROM care_actions WHERE request_id = $1', [foreignBatch]);
  }
});

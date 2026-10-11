import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

let detailStatus = 200;
let detailDelay = 0;
let watering = '10-14';
const harness = createApiHarness({ testClock: true, perenual: path => path.endsWith('species-list')
  ? { status: 200, body: { data: [{ id: 2001, scientific_name: ['Testoria care'] }] } }
  : { status: detailStatus, delay: detailDelay, body: { watering_general_benchmark: { value: watering, unit: 'days' }, sunlight: ['part shade'], pruning_month: ['April'], indoor: true } },
});
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let plantId: string;
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  ({ request } = await harness.login());
});

test('adding never waits on Perenual, then the queue changes the schedule and field sources', async () => {
  await request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T12:00:00Z' });
  const site = await (await request('/api/sites', 'POST', { name: 'Test care room', location: 'indoor', light: 'part_sun', grow_light: false, humidity: 'normal', draft: false })).json();
  harness.setGbif({ usageKey: 910090, scientificName: 'Testoria care Inventor', canonicalName: 'Testoria care', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const search = await (await request('/api/species/search?q=Testoria%20care')).json();
  detailDelay = 1500;
  const start = performance.now();
  const added = await request('/api/plants', 'POST', { ...fixturePlant, pot_size_cm: 12, site_id: site.id, search_id: search.search_id, gbif_key: 910090 });
  expect(added.status).toBe(201);
  expect(performance.now() - start).toBeLessThan(1000);
  plantId = (await added.json()).id;
  const before = await (await request('/api/plants/' + plantId + '/tasks')).json();
  expect(before.water.intervalDays).toBe(5);
  expect(before.water.basedOn[0]).toMatchObject({ value: 7, source: 'rule', adjustment: 'Weekly watering is the starting estimate when species data is missing.' });
  expect((await request('/__test/care-worker', 'POST', {})).status).toBe(200);
  const after = await (await request('/api/plants/' + plantId + '/tasks')).json();
  expect(after.water.intervalDays).toBe(9);
  expect(after.water.basedOn[0]).toMatchObject({ value: 12, source: 'perenual', fetchedAt: '2030-06-01T12:00:00.000Z' });
  const card = await (await request('/api/species/910090/care')).json();
  expect(card.profile.light).toMatchObject({ value: 'part_shade', source: 'perenual' });
  expect(card.profile.humidityNeed).toMatchObject({ value: 'medium', source: 'rule', fetchedAt: null });
  expect(card.profile.temperatureRangeC).toEqual({ value: null, source: 'unknown', fetchedAt: null });
  expect(card.fetch.status).toBe('complete');
  const today = await (await request('/api/tasks?view=today')).json();
  expect(today.groups.water[0].basedOn[0]).toMatchObject({ value: 12, source: 'perenual' });
  detailDelay = 0;
});

test('an override survives a source refresh, appears in Based on, and can be removed', async () => {
  expect((await request('/api/species/910090/care/overrides/wateringIntervalDays', 'PUT', { value: 20 })).status).toBe(200);
  watering = '3';
  expect((await request('/api/species/910090/care/refresh', 'POST', {})).status).toBe(202);
  await request('/__test/care-worker', 'POST', {});
  const card = await (await request('/api/species/910090/care')).json();
  expect(card.profile.wateringIntervalDays).toMatchObject({ value: 20, source: 'override' });
  const tasks = await (await request('/api/plants/' + plantId + '/tasks')).json();
  expect(tasks.water.intervalDays).toBe(15);
  expect(tasks.water.basedOn[0]).toMatchObject({ value: 20, source: 'override' });
  expect((await request('/api/species/910090/care/overrides/wateringIntervalDays', 'DELETE')).status).toBe(204);
  expect((await (await request('/api/species/910090/care')).json()).profile.wateringIntervalDays).toMatchObject({ value: 3, source: 'perenual' });
});

test.each([
  ['wateringIntervalDays', 0], ['wateringIntervalDays', '12'], ['wateringIntervalDays', 366],
  ['fertilizingIntervalDays', -1], ['light', 'bright'], ['humidityNeed', 'wet'],
  ['temperatureRangeC', { min: 30, max: 10 }], ['hardiness', { min: 0, max: 14 }],
  ['poisonousToPets', 1], ['poisonousToHumans', 'false'], ['indoor', null], ['cycle', 'evergreen'],
  ['dimensions', { min: 1, max: 2, unit: 'feet' }], ['pruningMonths', [0, 13]], ['pruningMonths', [3, 3]],
  ['pests', ['']], ['madeUp', 12],
])('rejects invalid %s override without changing the care profile', async (field, value) => {
  const before = await (await request('/api/species/910090/care')).json();
  expect((await request('/api/species/910090/care/overrides/' + field, 'PUT', { value })).status).toBe(422);
  expect((await (await request('/api/species/910090/care')).json()).profile).toEqual(before.profile);
});

test.each([
  ['light', 'full_sun'], ['humidityNeed', 'low'], ['temperatureRangeC', { min: 15, max: 25 }],
  ['hardiness', { min: 9, max: 11 }], ['poisonousToPets', false], ['poisonousToHumans', true],
  ['cycle', 'perennial'], ['dimensions', { min: 30, max: 100, unit: 'cm' }], ['pruningMonths', [4, 5]],
  ['pests', ['Scale insects']], ['indoor', true], ['fertilizingIntervalDays', 30],
])('accepts and removes a typed %s override', async (field, value) => {
  expect((await request('/api/species/910090/care/overrides/' + field, 'PUT', { value })).status).toBe(200);
  expect((await (await request('/api/species/910090/care')).json()).profile[field]).toMatchObject({ value, source: 'override', fetchedAt: '2030-06-01T12:00:00.000Z' });
  expect((await request('/api/species/910090/care/overrides/' + field, 'DELETE')).status).toBe(204);
});

test('refreshes stale data automatically after thirty days while retaining overrides', async () => {
  await request('/api/species/910090/care/overrides/wateringIntervalDays', 'PUT', { value: 22 });
  watering = '16';
  await request('/__test/clock', 'POST', { action: 'set', at: '2030-07-02T12:00:00Z' });
  ({ request } = await harness.login());
  await request('/__test/care-worker', 'POST', {});
  expect((await (await request('/api/species/910090/care')).json()).profile.wateringIntervalDays).toMatchObject({ value: 22, source: 'override' });
  await request('/api/species/910090/care/overrides/wateringIntervalDays', 'DELETE');
  expect((await (await request('/api/species/910090/care')).json()).profile.wateringIntervalDays).toMatchObject({ value: 16, source: 'perenual', fetchedAt: '2030-07-02T12:00:00.000Z' });
});

test('refuses unauthenticated reads and writes, wrong-origin edits, unknown species and malformed JSON', async () => {
  for (const [path, method] of [
    ['/api/species/910090/care', 'GET'], ['/api/species/910090/care/refresh', 'POST'],
    ['/api/species/910090/care/overrides/light', 'PUT'], ['/api/species/910090/care/overrides/light', 'DELETE'],
    ['/__test/care-worker', 'POST'],
  ]) expect((await request(path, method, undefined, '')).status).toBe(401);
  for (const key of ['999999', '999999999999', 'invalid']) expect((await request('/api/species/' + key + '/care')).status).toBe(404);
  const { cookie } = await harness.login();
  expect((await fetch(harness.base + '/api/species/910090/care/overrides/light', { method: 'PUT', headers: { cookie, origin: 'https://wrong.invalid', 'content-type': 'application/json' }, body: JSON.stringify({ value: 'full_sun' }) })).status).toBe(403);
  expect((await fetch(harness.base + '/api/species/910090/care/overrides/light', { method: 'PUT', headers: { cookie, 'content-type': 'application/json' }, body: '{' })).status).toBe(400);
});

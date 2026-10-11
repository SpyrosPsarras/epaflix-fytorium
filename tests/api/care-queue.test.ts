import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

let id = 2002;
let status = 429;
const harness = createApiHarness({ testClock: true, careEnv: { PERENUAL_DAILY_CAP: '2', PERENUAL_PER_MINUTE: '1', PERENUAL_MAX_ATTEMPTS: '2' }, perenual: path => path.endsWith('species-list')
  ? { status: 200, body: { data: [{ id, scientific_name: ['Testoria queued'] }] } }
  : { status, ...(status === 429 ? { headers: { 'retry-after': '120' } } : {}), body: { watering_general_benchmark: { value: '18', unit: 'days' } } },
});
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let site: string;
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  ({ request } = await harness.login());
  site = (await (await request('/api/sites', 'POST', { name: 'Test queue room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json()).id;
});
async function add(key: number) {
  harness.setGbif({ usageKey: key, scientificName: 'Testoria queued Inventor', canonicalName: 'Testoria queued', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const result = await (await request('/api/species/search?q=Queueplant' + key)).json();
  expect((await request('/api/plants', 'POST', { ...fixturePlant, site_id: site, search_id: result.search_id, gbif_key: key })).status).toBe(201);
}
const card = (key = 910091) => request(`/api/species/${key}/care`).then(r => r.json());
const tick = () => request('/__test/care-worker', 'POST', {});
async function time(at: string) {
  await request('/__test/clock', 'POST', { action: 'set', at });
  ({ request } = await harness.login());
}

test('throttles individual search and detail requests, backs off 429, caps daily calls and later succeeds', async () => {
  await add(910091);
  await tick();
  expect((await card()).fetch).toMatchObject({ status: 'pending', attempts: 0, next_at: '2030-06-01T12:01:00.000Z' });
  expect((await card()).profile.wateringIntervalDays.source).toBe('rule');
  await tick();
  expect((await card()).fetch.status).toBe('pending');
  await time('2030-06-01T12:01:00Z');
  await tick();
  expect((await card()).fetch).toMatchObject({ status: 'pending', attempts: 1, next_at: '2030-06-01T12:03:00.000Z' });
  status = 200;
  await time('2030-06-01T12:02:59Z');
  await tick();
  expect((await card()).profile.wateringIntervalDays.source).toBe('rule');
  await time('2030-06-01T12:03:00Z');
  await tick();
  expect((await card()).fetch).toMatchObject({ status: 'pending', attempts: 1, next_at: '2030-06-02T00:00:00.000Z' });
  await time('2030-06-02T00:00:00Z');
  await tick();
  expect((await card()).fetch.status).toBe('complete');
  expect((await card()).profile.wateringIntervalDays).toMatchObject({ value: 18, source: 'perenual' });
});

test('transient failures stop at the attempt cap and never replace good cached data', async () => {
  status = 500;
  await time('2030-06-02T00:01:00Z');
  await request('/api/species/910091/care/refresh', 'POST', {});
  await tick();
  expect((await card()).fetch).toMatchObject({ status: 'pending', attempts: 1 });
  await time('2030-06-03T00:00:00Z');
  await tick();
  expect((await card()).fetch).toMatchObject({ status: 'failed', attempts: 2 });
  status = 200;
  await time('2030-06-03T00:01:00Z');
  await tick();
  expect((await card()).fetch.status).toBe('failed');
  expect((await card()).profile.wateringIntervalDays).toMatchObject({ value: 18, source: 'perenual' });
});

test('above-limit species become unavailable without calling details or retrying', async () => {
  id = 4001;
  await add(910092);
  await tick();
  expect((await card(910092)).fetch.status).toBe('not_available');
  await time('2030-06-04T00:00:00Z');
  await tick();
  expect((await card(910092)).fetch).toMatchObject({ status: 'not_available', attempts: 1 });
  expect((await card(910092)).profile.wateringIntervalDays).toMatchObject({ value: 7, source: 'rule' });
});

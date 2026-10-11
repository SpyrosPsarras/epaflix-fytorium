import { expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({
  testClock: true,
  careEnv: { PERENUAL_DAILY_CAP: '100', PERENUAL_PER_MINUTE: '2' },
  perenual: path => path.endsWith('species-list')
    ? { status: 200, body: { data: [{ id: 2093, scientific_name: ['Testoria quota'] }] } }
    : { status: 200, body: { watering_general_benchmark: { value: '12', unit: 'days' } } },
});

test('test-only quota reset isolates repeated care fetches at the same frozen time without clearing cached care', async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  const { request } = await harness.login();
  const site = await (await request('/api/sites', 'POST', { name: 'Test quota room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  async function add(key: number) {
    harness.setGbif({ usageKey: key, scientificName: 'Testoria quota Inventor', canonicalName: 'Testoria quota', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
    const search = await (await request('/api/species/search?q=Quota' + key)).json();
    expect((await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: key })).status).toBe(201);
  }
  const card = (key: number) => request('/api/species/' + key + '/care').then(response => response.json());
  await add(910093);
  await request('/__test/care-worker', 'POST', {});
  expect((await card(910093)).profile.wateringIntervalDays).toMatchObject({ value: 12, source: 'perenual' });
  await add(910094);
  await request('/__test/care-worker', 'POST', {});
  expect((await card(910094)).fetch).toMatchObject({ status: 'pending', next_at: '2030-06-01T12:01:00.000Z' });

  expect((await request('/__test/care-quota/perenual', 'DELETE', undefined, '')).status).toBe(401);
  expect((await request('/__test/care-quota/unknown', 'DELETE')).status).toBe(404);
  expect((await request('/__test/care-quota/perenual', 'DELETE')).status).toBe(200);
  expect((await card(910093)).profile.wateringIntervalDays).toMatchObject({ value: 12, source: 'perenual' });
  // Reset only accounting; the normal refresh command makes the deferred job eligible again.
  await request('/api/species/910094/care/refresh', 'POST', {});
  await request('/__test/care-worker', 'POST', {});
  expect((await card(910094)).profile.wateringIntervalDays).toMatchObject({ value: 12, source: 'perenual', fetchedAt: '2030-06-01T12:00:00.000Z' });
  await harness.restartApp({ FYTORIUM_TEST_CLOCK: 'false', NODE_ENV: 'production' });
  expect((await request('/__test/care-quota/perenual', 'DELETE')).status).toBe(404);
});

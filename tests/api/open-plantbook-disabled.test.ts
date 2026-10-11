import { expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
let calls = 0;
const harness = createApiHarness({ testClock: true, careEnv: { OPEN_PLANTBOOK_API_KEY: '' }, openPlantbook: () => { calls++; return { status: 500, body: {} }; } });
test('without a key Plantbook is disabled and makes no requests', async () => {
  const { request } = await harness.login();
  const site = await (await request('/api/sites', 'POST', { name: 'Test disabled sensor room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  expect((await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).status).toBe(201);
  await request('/__test/care-worker', 'POST', {});
  const care = await (await request('/api/species/910001/care')).json();
  expect(care.fetches).toContainEqual(expect.objectContaining({ source: 'open_plantbook', status: 'disabled', attempts: 0 }));
  expect(await (await request('/species/910001')).text()).toContain('Open Plantbook not configured');
  expect((await request('/api/species/910001/care/retry-match', 'POST')).status).toBe(409);
  expect(calls).toBe(0);
});

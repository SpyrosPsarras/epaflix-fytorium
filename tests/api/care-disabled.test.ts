import { expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

let calls = 0;
const harness = createApiHarness({ testClock: true, careEnv: { PERENUAL_API_KEY: '' }, perenual: () => {
  calls++;
  return { status: 500, body: {} };
} });

test('without a key the card shows not configured, with no pollable status or outbound calls', async () => {
  const { request } = await harness.login();
  const site = await (await request('/api/sites', 'POST', { name: 'Test disabled room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  expect((await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).status).toBe(201);
  for (let i = 0; i < 2; i++) {
    await request('/__test/care-worker', 'POST', {});
    const card = await (await request('/api/species/910001/care')).json();
    expect(card.fetches).toMatchObject([{ source: 'perenual', status: 'disabled', attempts: 0 }]);
  }
  expect(await (await request('/species/910001')).text()).toContain('Perenual not configured');
  expect(calls).toBe(0);
});

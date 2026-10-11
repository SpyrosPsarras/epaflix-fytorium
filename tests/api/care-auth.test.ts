import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

let status = 401;
let calls = 0;
const harness = createApiHarness({ testClock: true, perenual: path => {
  calls++;
  return { status, body: path.endsWith('species-list')
    ? { data: [{ id: 2003, scientific_name: ['Testoria auth'] }] }
    : { watering_general_benchmark: { value: '12', unit: 'days' } } };
} });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
beforeAll(async () => {
  ({ request } = await harness.login());
  const site = await (await request('/api/sites', 'POST', { name: 'Test auth room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  for (const key of [910093, 910094]) {
    harness.setGbif({ usageKey: key, scientificName: 'Testoria auth Inventor', canonicalName: 'Testoria auth', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
    const search = await (await request('/api/species/search?q=Authplant' + key)).json();
    expect((await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: key })).status).toBe(201);
  }
});
const card = (key: number) => request(`/api/species/${key}/care`).then(r => r.json());
const tick = () => request('/__test/care-worker', 'POST', {});
const logs = () => harness.appOutput.split('paused after an authentication failure').length - 1;

test.each([401, 403])('%s pauses the source once, preserves all jobs and resumes only on explicit refresh', async failure => {
  status = failure;
  await request('/api/species/910093/care/refresh', 'POST', {});
  const beforeCalls = calls;
  const beforeLogs = logs();
  await tick();
  for (const key of [910093, 910094]) expect((await card(key)).fetch.status).toBe('auth_failed');
  expect((await card(910093)).fetch.attempts).toBe(0);
  expect(logs()).toBe(beforeLogs + 1);
  status = 200;
  await tick();
  await tick();
  expect(calls).toBe(beforeCalls + 1);
  expect(logs()).toBe(beforeLogs + 1);
  await request('/api/species/910093/care/refresh', 'POST', {});
  await tick();
  await tick();
  for (const key of [910093, 910094]) expect((await card(key)).fetch.status).toBe('complete');
});

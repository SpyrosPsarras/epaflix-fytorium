import { expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true });
test('plant count uses active shared plants, not Today filters, and plant weather is household scoped', async () => {
  const owner = await harness.login();
  const site = await (await owner.request('/api/sites', 'POST', { name: 'API room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  const add = async (nickname: string) => (await (await owner.request('/api/plants', 'POST', { ...fixturePlant, nickname, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json()).id;
  const shared = await add('Shared test plant');
  const privatePlant = await add('Private test plant');
  const dead = await add('Graveyard test plant');
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [shared, dead] })).json();
  const sitter = await harness.login('page-data-sitter', {}, invite.link.split('/').at(-1));
  await owner.request('/api/plants/' + dead + '/graveyard', 'POST');
  await owner.request('/api/tasks/settings', 'PUT', { sort: 'site', filter: 'outdoor', show_completed: true });
  expect(await (await owner.request('/api/plants/count')).json()).toEqual({ count: 2 });
  expect(await (await sitter.request('/api/plants/count')).json()).toEqual({ count: 1 });
  const weather = await owner.request('/api/plants/' + shared + '/weather');
  expect(weather.status).toBe(200);
  expect(await weather.json()).toEqual(await (await owner.request('/api/weather')).json());
  for (const id of [shared, privatePlant]) expect((await sitter.request('/api/plants/' + id + '/weather')).status).toBe(403);
  expect((await owner.request('/api/plants/00000000-0000-4000-8000-000000000000/weather')).status).toBe(404);
});

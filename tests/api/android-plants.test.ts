import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness();
let owner: Awaited<ReturnType<typeof harness.login>>;
let family: typeof owner;
let sitter: typeof owner;
let shared: string;
beforeAll(async () => {
  owner = await harness.login();
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Synthetic Android room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  for (const nickname of ['Synthetic shared fern', 'Synthetic private fern']) {
    const plant = await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, nickname, search_id: search.search_id, gbif_key: 910001 })).json();
    if (nickname === 'Synthetic shared fern') shared = plant.id;
  }
  for (const role of ['family', 'sitter'] as const) {
    const invite = await (await owner.request('/api/household/invites', 'POST', { role, plant_ids: role === 'sitter' ? [shared] : [] })).json();
    const member = await harness.login('synthetic-android-' + role, {}, invite.link.split('/').at(-1));
    if (role === 'family') family = member; else sitter = member;
  }
});
for (const role of ['owner', 'family', 'sitter'] as const) test(`${role} receives plant choices only when editing is allowed`, async () => {
  const member = { owner, family, sitter }[role];
  const response = await member.request('/api/choices');
  expect(response.status).toBe(role === 'sitter' ? 403 : 200);
  if (response.ok) expect(await response.json()).toMatchObject({
    plant: { choices: { pot_type: ['plastic', 'terracotta', 'ceramic', 'glass', 'metal', 'wood', 'fabric', 'in_ground', 'other'], soil_type: ['all_purpose', 'cactus', 'orchid', 'aroid', 'other'] },
      numbers: ['pot_size_cm', 'window_distance_cm'], booleans: ['drainage', 'grow_light', 'near_ac', 'near_heater'], health: ['poor', 'fair', 'good', 'very_good'] },
  });
});
test('sitter plant lists include all shared headers but no private fields, counts or graveyard plants', async () => {
  const response = await sitter.request('/api/plants');
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual([expect.objectContaining({ id: shared, display_name: 'Synthetic shared fern' })]);
  const headers = await (await sitter.request('/api/plants')).json();
  expect(headers[0]).not.toHaveProperty('site_id');
  expect(headers[0]).not.toHaveProperty('pot_type');
  expect((await sitter.request('/api/plants?status=graveyard')).status).toBe(403);
  expect((await sitter.request('/api/plants?count=true')).status).toBe(403);
  await owner.request(`/api/plants/${shared}/graveyard`, 'POST');
  expect(await (await sitter.request('/api/plants')).json()).toEqual([]);
});
test('unauthenticated choices and lists require login', async () => {
  for (const path of ['/api/choices', '/api/plants']) expect((await fetch(harness.base + path)).status).toBe(401);
});

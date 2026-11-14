import { beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness();
let owner: Awaited<ReturnType<typeof harness.login>>;
const read = async (path: string) => (await owner.request(path)).json();

beforeAll(async () => { owner = await harness.login(); });

test('plants, species, species care and species changes name each species the way its plants show it', async () => {
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Synthetic naming room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await read('/api/species/search?q=Testoria%20lucida');
  const add = (nickname: string | null) => owner.request('/api/plants', 'POST', { ...fixturePlant, nickname, site_id: site.id, search_id: search.search_id, gbif_key: 910001 });
  const named = await (await add('Synthetic named fern')).json();
  expect((await add(null)).status).toBe(201);
  expect(await read('/api/species/910001')).toMatchObject({ common_name: null });
  expect((await owner.request('/api/species/910001/names', 'POST', { name: 'Synthetic trialleaf', language: 'en' })).status).toBe(201);
  expect(await read('/api/plants/' + named.id)).toMatchObject({ display_name: 'Synthetic named fern', common_name: 'Synthetic trialleaf' });
  expect(await read('/api/species/910001')).toMatchObject({ common_name: 'Synthetic trialleaf',
    names: expect.arrayContaining([expect.objectContaining({ name: 'Synthetic trialleaf', source: 'owner', renamed_plant_count: 1 })]) });
  expect((await read('/api/species/910001/care')).species).toMatchObject({ canonical_name: 'Testoria lucida', common_name: 'Synthetic trialleaf' });

  harness.setGbif({ usageKey: 910019, scientificName: 'Testoria correcta Inventor', canonicalName: 'Testoria correcta', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const corrected = await read('/api/species/search?q=Testoria%20correcta');
  expect((await owner.request('/api/plants/' + named.id + '/species', 'POST', { search_id: corrected.search_id, gbif_key: 910019, confirmed: true })).status).toBe(200);
  expect(await read('/api/plants/' + named.id + '/species-changes')).toEqual([expect.objectContaining({
    previous_name: 'Testoria lucida Inventor', previous_display_name: 'Synthetic trialleaf', next_name: 'Testoria correcta Inventor', next_display_name: 'Testoria correcta' })]);

  const invalid = await owner.request('/api/species/910001/care/overrides/temperatureRangeC', 'PUT', { value: { min: 30, max: 20 } });
  expect(invalid.status).toBe(422);
  expect((await invalid.json()).message).toBe('Enter a valid value for temperature range in °C.');
});

test('each household name counts the plants it names in any language, blank nicknames included', async () => {
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Synthetic language room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  harness.setGbif({ usageKey: 910031, scientificName: 'Testoria lingua Inventor', canonicalName: 'Testoria lingua', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const search = await read('/api/species/search?q=Testoria%20lingua');
  const add = async (nickname: string | null) => {
    const response = await owner.request('/api/plants', 'POST', { ...fixturePlant, nickname, site_id: site.id, search_id: search.search_id, gbif_key: 910031 });
    expect(response.status).toBe(201);
    return response.json();
  };
  await add(null);
  const blank = await add('Synthetic soon blank');
  await add('Synthetic named lantern');
  const fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  try { await fixtureDb.query("UPDATE plants SET nickname = '  ' WHERE id = $1", [blank.id]); } finally { await fixtureDb.end(); }
  for (const [name, language] of [['Synthetic lantern', 'en'], ['Syntetisk lykt', 'nb'], ['Syntetisk lampe', 'nb']]) {
    expect((await owner.request('/api/species/910031/names', 'POST', { name, language })).status).toBe(201);
  }
  const species = await read('/api/species/910031');
  expect(species.common_name).toBe('Synthetic lantern');
  expect(species.names).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'Synthetic lantern', language: 'en', renamed_plant_count: 2 }),
    expect.objectContaining({ name: 'Syntetisk lykt', language: 'nb', renamed_plant_count: 2 }),
    expect.objectContaining({ name: 'Syntetisk lampe', language: 'nb', renamed_plant_count: 0 }),
  ]));
});

test('removing a Norwegian household name equal to the English one renames no plant, because Norwegian falls back to English', async () => {
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Synthetic echo room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  harness.setGbif({ usageKey: 910032, scientificName: 'Testoria echo Inventor', canonicalName: 'Testoria echo', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const search = await read('/api/species/search?q=Testoria%20echo');
  expect((await owner.request('/api/plants', 'POST', { ...fixturePlant, nickname: null, site_id: site.id, search_id: search.search_id, gbif_key: 910032 })).status).toBe(201);
  for (const language of ['en', 'nb']) expect((await owner.request('/api/species/910032/names', 'POST', { name: 'Synthetic echo', language })).status).toBe(201);
  expect((await read('/api/species/910032')).names).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'Synthetic echo', language: 'nb', renamed_plant_count: 0 }),
    expect.objectContaining({ name: 'Synthetic echo', language: 'en', renamed_plant_count: 1 }),
  ]));
});

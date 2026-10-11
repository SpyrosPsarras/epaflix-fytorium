import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let siteId: string;
const exact = { usageKey: 910001, scientificName: 'Testoria lucida Inventor', canonicalName: 'Testoria lucida', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 };
const attributes = {
  nickname: 'Test sprout', cultivar: 'Test silver', birthday: '2020-05-06',
  pot_type: 'terracotta', pot_size_cm: 18, drainage: true, soil_type: 'all_purpose',
  plant_size: 'medium', growth_stage: 'young', window_distance_cm: 70,
  grow_light: false, near_ac: false, near_heater: true, fertilizer_type: 'liquid',
};

beforeAll(async () => {
  ({ request } = await harness.login());
  siteId = (await (await request('/api/sites', 'POST', {
    name: 'Test plant room', location: 'indoor', light: 'part_sun', grow_light: false,
    humidity: 'normal', draft: false,
  })).json()).id;
});

async function search(q = 'Testoria lucida') {
  const response = await request('/api/species/search?q=' + encodeURIComponent(q));
  expect(response.status).toBe(200);
  return response.json();
}

const fuzzy = { ...exact, usageKey: 910002, scientificName: 'Testoria nebulosa Inventor', canonicalName: 'Testoria nebulosa', matchType: 'FUZZY', confidence: 96, alternatives: [{ ...exact, usageKey: 910003, scientificName: 'Testoria alternativa Inventor', canonicalName: 'Testoria alternativa' }] };

async function add(searchId: string, key = 910001, extra = {}) {
  return request('/api/plants', 'POST', { ...attributes, site_id: siteId, search_id: searchId, gbif_key: key, ...extra });
}

test('requires confirmation for the fuzzy key and every alternative, then accepts them', async () => {
  harness.setGbif(fuzzy);
  const result = await search('Testoria neblosa');
  expect(result.candidates).toEqual([
    expect.objectContaining({ gbif_key: 910002, requires_confirmation: true }),
    expect.objectContaining({ gbif_key: 910003, requires_confirmation: true }),
  ]);
  const before = await (await request('/api/plants')).json();
  for (const key of [910002, 910003]) {
    for (const confirmed of [undefined, false, 'true']) {
      const refused = await add(result.search_id, key, { confirmed, confidence: 100, match_type: 'EXACT' });
      expect(refused.status).toBe(422);
      expect((await refused.json()).message).toContain('Confirm');
    }
  }
  expect(await (await request('/api/plants')).json()).toEqual(before);
  expect((await add(result.search_id, 999999, { confirmed: true })).status).toBe(422);
  for (const key of [910002, 910003]) expect((await add(result.search_id, key, { confirmed: true })).status).toBe(201);
});

test('NONE is refused without storing a plant', async () => {
  const before = await (await request('/api/plants')).json();
  harness.setGbif({ matchType: 'NONE', confidence: 0 });
  const response = await request('/api/species/search?q=Unknownia');
  expect(response.status).toBe(422);
  expect((await response.json()).message).toContain('No plant species');
  expect((await add('not-a-search', 910099, { confirmed: true })).status).toBe(422);
  expect(await (await request('/api/plants')).json()).toEqual(before);
});

test('adds the same species twice with one catalogue entry and searches locally during an outage', async () => {
  harness.setGbif(exact);
  const result = await search();
  for (let index = 0; index < 2; index++) expect((await add(result.search_id, 910001, { nickname: null })).status).toBe(201);
  const catalogue = await request('/api/species');
  expect(catalogue.status).toBe(200);
  expect((await catalogue.json()).filter((species: { gbif_key: number }) => species.gbif_key === 910001)).toEqual([
    expect.objectContaining({ scientific_name: 'Testoria lucida Inventor', canonical_name: 'Testoria lucida', rank: 'SPECIES', fetched_at: expect.any(String) }),
  ]);
  harness.setGbif({}, 503);
  const local = await search('LUCIDA');
  expect(local.candidates).toEqual([expect.objectContaining({ gbif_key: 910001, match_type: null, confidence: null, requires_confirmation: true })]);
  expect((await add(local.search_id, 910001, { nickname: null })).status).toBe(422);
  expect((await add(local.search_id, 910001, { nickname: null, confirmed: true })).status).toBe(201);
  expect(await (await request('/api/plants')).json()).toEqual(expect.arrayContaining([expect.objectContaining({ display_name: 'Testoria lucida' })]));
});

test('an exact canonical or scientific name needs no confirmation, even during an outage', async () => {
  harness.setGbif({}, 503);
  for (const query of ['  TESTORIA LUCIDA  ', 'testoria lucida inventor']) {
    const result = await search(query);
    expect(result.candidates).toEqual([expect.objectContaining({ gbif_key: 910001, requires_confirmation: false })]);
    expect((await add(result.search_id)).status).toBe(201);
  }
});

test('local partial hits do not hide GBIF candidates or invent match metadata', async () => {
  harness.setGbif({ ...exact, usageKey: 910031, scientificName: 'Testoria nova Inventor', canonicalName: 'Testoria nova' });
  const result = await search('Testoria');
  expect(result.candidates).toEqual(expect.arrayContaining([
    expect.objectContaining({ gbif_key: 910031, match_type: 'EXACT', confidence: 99, requires_confirmation: false }),
    expect.objectContaining({ gbif_key: 910001, match_type: null, confidence: null, requires_confirmation: true }),
  ]));
  expect((await add(result.search_id, 910001)).status).toBe(422);
  expect((await add(result.search_id, 910001, { confirmed: true })).status).toBe(201);
  expect((await add(result.search_id, 910031)).status).toBe(201);
});

test('edits every attribute, clears optional values and moves to another household site', async () => {
  const result = await search();
  const plant = await (await add(result.search_id)).json();
  const otherSite = await (await request('/api/sites', 'POST', {
    name: 'Test moved room', location: 'outdoor', light: 'full_sun', grow_light: false, humidity: 'dry', draft: true,
  })).json();
  const edited = { site_id: otherSite.id, nickname: null, cultivar: null, birthday: null,
    pot_type: 'in_ground', pot_size_cm: null, drainage: false, soil_type: 'other',
    plant_size: 'very_large', growth_stage: 'mature', window_distance_cm: null,
    grow_light: true, near_ac: true, near_heater: false, fertilizer_type: 'slow_release' };
  expect((await request('/api/plants/' + plant.id, 'PUT', edited)).status).toBe(200);
  expect(await (await request('/api/plants/' + plant.id)).json()).toMatchObject({ ...edited, display_name: 'Testoria lucida', scientific_name: 'Testoria lucida Inventor', site_name: 'Test moved room' });
  expect(await (await request('/api/plants?site_id=' + otherSite.id)).json()).toEqual([expect.objectContaining({ id: plant.id })]);
});

test('rejects invalid attributes on add and edit without writing anything', async () => {
  const result = await search();
  const plant = await (await add(result.search_id)).json();
  const before = await (await request('/api/plants')).json();
  const cases: [string, unknown][] = [
    ['pot_type', 'stone'], ['soil_type', 'sand'], ['plant_size', 'huge'], ['growth_stage', 'old'], ['fertilizer_type', 'powder'],
    ['pot_type', null], ['pot_size_cm', 0], ['pot_size_cm', -1], ['pot_size_cm', 2.5], ['window_distance_cm', '70'], ['window_distance_cm', 2147483648],
    ['drainage', 'true'], ['grow_light', 1], ['near_ac', null], ['near_heater', undefined],
    ['nickname', 42], ['cultivar', false], ['birthday', '2999-01-01'], ['birthday', '2020-02-30'], ['birthday', 'tomorrow'],
    ['site_id', null], ['site_id', 'not-an-id'], ['site_id', '00000000-0000-4000-8000-000000000000'],
  ];
  for (const [field, value] of cases) {
    for (const [path, method] of [['/api/plants', 'POST'], ['/api/plants/' + plant.id, 'PUT']]) {
      const response = await request(path, method, { ...attributes, site_id: siteId, search_id: result.search_id, gbif_key: 910001, [field]: value });
      expect(response.status, method + ' ' + field + '=' + String(value)).toBe(422);
      expect((await response.json()).errors[field]).toEqual(expect.any(String));
    }
  }
  for (const input of [null, [], 'invalid']) expect((await request('/api/plants', 'POST', input)).status).toBe(400);
  expect(await (await request('/api/plants')).json()).toEqual(before);
});

test('counts plants without returning their attributes and shows the total on home', async () => {
  const site = await (await request('/api/sites', 'POST', {
    name: 'Test counted plant room', location: 'indoor', light: 'part_sun', grow_light: false,
    humidity: 'normal', draft: false,
  })).json();
  const path = '/api/plants?count=true&site_id=' + site.id;
  expect(await (await request(path)).json()).toEqual({ count: 0 });
  const result = await search();
  for (let i = 0; i < 2; i++) expect((await add(result.search_id, 910001, { site_id: site.id })).status).toBe(201);
  expect(await (await request(path)).json()).toEqual({ count: 2 });
  const total = await (await request('/api/plants?count=true')).json();
  expect(await (await request('/')).text()).toContain(total.count + ' plants');
});

test('requires a household session for all plant and species operations', async () => {
  const result = await search();
  const plant = await (await add(result.search_id)).json();
  for (const [path, method, input] of [
    ['/api/species', 'GET', undefined], ['/api/species/search?q=Testoria', 'GET', undefined],
    ['/api/plants', 'GET', undefined], ['/api/plants?count=true', 'GET', undefined], ['/api/plants', 'POST', attributes],
    ['/api/plants/' + plant.id, 'GET', undefined], ['/api/plants/' + plant.id, 'PUT', attributes],
  ] as const) {
    expect((await request(path, method, input, '')).status).toBe(401);
  }
});

test('GBIF outages, timeout and malformed payloads return a clear bounded error', async () => {
  for (const [body, status, delay] of [[{}, 503, 0], [{}, 200, 6000], [null, 200, 0]] as const) {
    harness.setGbif(body, status, delay);
    const start = performance.now();
    const response = await request('/api/species/search?q=Outageia');
    expect(response.status).toBe(503);
    expect((await response.json()).message).toContain('GBIF');
    expect(performance.now() - start).toBeLessThan(5000);
  }
});

test('low confidence, higher-rank matching and lower taxon ranks require confirmation; nonplants and genus matches are refused', async () => {
  for (const [key, extra] of [[910011, { confidence: 89 }], [910012, { matchType: 'HIGHERRANK' }], [910013, { rank: 'SUBSPECIES' }]] as const) {
    harness.setGbif({ ...exact, usageKey: key, scientificName: 'Cautiousia testensis Inventor', canonicalName: 'Cautiousia testensis', ...extra });
    const result = await search('Uncachedia ' + key);
    expect(result.candidates[0].requires_confirmation).toBe(true);
    expect((await add(result.search_id, key)).status).toBe(422);
    expect((await add(result.search_id, key, { confirmed: true })).status).toBe(201);
  }
  for (const extra of [{ kingdom: 'Animalia' }, { rank: 'GENUS', matchType: 'HIGHERRANK' }]) {
    harness.setGbif({ ...exact, ...extra });
    expect((await request('/api/species/search?q=Unsupportedia')).status).toBe(422);
  }
});

test('stores accepted synonym identity and uses its accepted name when supplied', async () => {
  harness.setGbif({ ...exact, usageKey: 910021, acceptedUsageKey: 910022, scientificName: 'Testoria prior Inventor', canonicalName: 'Testoria prior', acceptedScientificName: 'Testoria accepta Inventor', acceptedCanonicalName: 'Testoria accepta' });
  const result = await search('Synonymia');
  expect(result.candidates[0]).toMatchObject({ gbif_key: 910022, scientific_name: 'Testoria accepta Inventor' });
  expect((await add(result.search_id, 910021)).status).toBe(422);
  const plant = await (await add(result.search_id, 910022)).json();
  expect(await (await request('/api/plants/' + plant.id)).json()).toMatchObject({ gbif_key: 910022, scientific_name: 'Testoria accepta Inventor' });
});

test('fetches an omitted accepted name without replacing a stored name with the synonym', async () => {
  harness.setGbif({ ...exact, usageKey: 910023, scientificName: 'Testoria stabilis Inventor', canonicalName: 'Testoria stabilis' });
  const accepted = await search('Testoria stabilis');
  expect((await add(accepted.search_id, 910023)).status).toBe(201);
  harness.setGbifSpecies(910023, { key: 910023, scientificName: 'Testoria stabilis Inventor', canonicalName: 'Testoria stabilis', rank: 'SPECIES', kingdom: 'Plantae' });
  harness.setGbif({ ...exact, usageKey: 910024, acceptedUsageKey: 910023, scientificName: 'Testoria vetusta Inventor', canonicalName: 'Testoria vetusta' });
  const result = await search('Testoria vetusta');
  expect(result.candidates).toEqual([expect.objectContaining({ gbif_key: 910023, scientific_name: 'Testoria stabilis Inventor', canonical_name: 'Testoria stabilis' })]);
  expect((await add(result.search_id, 910024)).status).toBe(422);
  expect((await add(result.search_id, 910023)).status).toBe(201);
  const catalogue = await (await request('/api/species')).json();
  expect(catalogue.filter((species: { gbif_key: number }) => species.gbif_key === 910023)).toEqual([
    expect.objectContaining({ scientific_name: 'Testoria stabilis Inventor', canonical_name: 'Testoria stabilis' }),
  ]);
});

test('search expires and birthdays use the controllable clock', async () => {
  const result = await search();
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 300001 });
  expect((await add(result.search_id)).status).toBe(422);
  await request('/__test/clock', 'POST', { action: 'set', at: '2001-01-01T00:00:00Z' });
  const current = await search();
  const response = await add(current.search_id);
  expect(response.status).toBe(422);
  expect((await response.json()).errors.birthday).toContain('future');
  await request('/__test/clock', 'POST', { action: 'reset' });
});

let fixtureDb: pg.Pool;
afterAll(async () => { if (fixtureDb) await fixtureDb.end(); });
test('plants and search choices cannot cross household boundaries', async () => {
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  // Household invitations have no API yet. SQL sets up membership only; sites and plants use HTTP.
  await fixtureDb.query('DROP INDEX households_singleton');
  const household = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test second household') RETURNING id");
  await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-second-owner', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  const other = await harness.login('synthetic-second-owner');
  const foreignSite = await (await other.request('/api/sites', 'POST', { name: 'Test foreign room', location: 'indoor', light: 'part_sun', grow_light: false, humidity: 'normal', draft: false })).json();
  const foreignSearch = await (await other.request('/api/species/search?q=Testoria%20lucida')).json();
  const foreignPlant = await (await other.request('/api/plants', 'POST', { ...attributes, site_id: foreignSite.id, search_id: foreignSearch.search_id, gbif_key: 910001 })).json();
  expect(foreignPlant.id).toEqual(expect.any(String));
  expect(await (await request('/api/plants?count=true&site_id=' + foreignSite.id)).json()).toEqual({ count: 0 });
  const result = await search();
  const ownPlant = await (await add(result.search_id)).json();
  const before = await (await request('/api/plants')).json();
  expect(before).not.toEqual(expect.arrayContaining([expect.objectContaining({ id: foreignPlant.id })]));
  for (const method of ['GET', 'PUT']) expect((await request('/api/plants/' + foreignPlant.id, method, method === 'PUT' ? { ...attributes, site_id: siteId } : undefined)).status).toBe(404);
  for (const [path, method] of [['/api/plants', 'POST'], ['/api/plants/' + ownPlant.id, 'PUT']]) {
    const response = await request(path, method, { ...attributes, site_id: foreignSite.id, search_id: result.search_id, gbif_key: 910001 });
    expect(response.status).toBe(422);
    expect((await response.json()).errors.site_id).toEqual(expect.any(String));
  }
  expect((await add(foreignSearch.search_id)).status).toBe(422);
  expect(await (await request('/api/plants')).json()).toEqual(before);
});

test('keeps a sound primary match when an alternative accepted-species lookup fails, dedupes and caps alternatives', async () => {
  harness.setGbif({ ...exact, usageKey: 910041, scientificName: 'Testoria bounded Inventor', canonicalName: 'Testoria bounded', alternatives: [
    { ...exact, usageKey: 910042, acceptedUsageKey: 910041, acceptedScientificName: 'Testoria bounded Inventor', acceptedCanonicalName: 'Testoria bounded' },
    { ...exact, usageKey: 910048, acceptedUsageKey: 910049 },
    { ...exact, usageKey: 910043 }, { ...exact, usageKey: 910044 }, { ...exact, usageKey: 910045 },
    { ...exact, usageKey: 910046 }, { ...exact, usageKey: 910047 },
  ] });
  const result = await search('Boundedia');
  expect(result.candidates.map((candidate: { gbif_key: number }) => candidate.gbif_key)).toEqual([910041, 910043, 910044, 910045]);
  expect((await add(result.search_id, 910041)).status).toBe(201);
});

test('adds an exact high-confidence plant and reads its species and attributes', async () => {
  harness.setGbif(exact);
  const result = await search();
  expect(result.candidates).toEqual([expect.objectContaining({ gbif_key: 910001, scientific_name: 'Testoria lucida Inventor', requires_confirmation: false })]);
  const response = await add(result.search_id);
  expect(response.status).toBe(201);
  const plant = await response.json();
  expect(await (await request('/api/plants/' + plant.id)).json()).toMatchObject({ ...attributes, gbif_key: 910001, scientific_name: 'Testoria lucida Inventor', display_name: 'Test sprout', site_name: 'Test plant room' });
  expect(await (await request('/api/plants')).json()).toEqual(expect.arrayContaining([expect.objectContaining({ id: plant.id })]));
});

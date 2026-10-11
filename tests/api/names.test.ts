import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let siteId: string;
const key = 910001;
const path = `/api/species/${key}`;
const gbifNames = { results: [
  { vernacularName: 'Trialleaf', language: 'eng' },
  { vernacularName: 'Prøveplante', language: 'nor' },
  { vernacularName: 'Δοκιμόφυτο', language: 'ell' },
  { vernacularName: 'Ignored synthetic', language: 'swe' },
], endOfRecords: true, offset: 0, limit: 500 };
const wikiNames = { entities: { Q910001: { id: 'Q910001', labels: {
  en: { language: 'en', value: 'Trial Wikileaf' },
  nb: { language: 'nb', value: 'Prøveblad' },
  el: { language: 'el', value: 'Δοκιμόφυτο' },
}, aliases: { el: [{ language: 'el', value: 'Δοκιμαστικό φύλλο' }] } } } };
const norNames = [{ taxonId: 980001, acceptedScientificName: { name: 'Testoria lucida', isSearchMatch: true },
  preferredVernacularNames: [{ name: 'Prøvespire', nameLanguageIso: 'nb', isSearchMatch: false }],
  scientificNameSynonyms: [], vernacularNameSynonyms: [] }];

beforeAll(async () => {
  ({ request } = await harness.login());
  siteId = (await (await request('/api/sites', 'POST', {
    name: 'Test name room', location: 'indoor', light: 'part_sun', grow_light: false, humidity: 'normal', draft: false,
  })).json()).id;
});
async function search(query: string) {
  const response = await request('/api/species/search?q=' + encodeURIComponent(query));
  expect(response.status).toBe(200);
  return response.json();
}
async function add(result: { search_id: string }, gbif_key = key, extra = {}) {
  return request('/api/plants', 'POST', { ...fixturePlant, site_id: siteId, gbif_key, search_id: result.search_id, ...extra });
}
async function species() {
  const response = await request(path);
  expect(response.status).toBe(200);
  return response.json();
}

test('adds without waiting for name sources, then fills multilingual names from all three sources', async () => {
  harness.setNames('gbif', gbifNames, 200, 1500);
  harness.setNames('wikidata', wikiNames, 200, 1500);
  harness.setNames('nortaxa', norNames, 200, 1500);
  const result = await search('Testoria lucida');
  const start = performance.now();
  expect((await add(result)).status).toBe(201);
  expect(performance.now() - start).toBeLessThan(1000);
  await expect.poll(async () => (await species()).names, { timeout: 10000 }).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'Trialleaf', language: 'en', source: 'gbif' }),
    expect.objectContaining({ name: 'Prøveplante', language: 'nb', source: 'gbif' }),
    expect.objectContaining({ name: 'Δοκιμόφυτο', language: 'el', source: 'gbif' }),
    expect.objectContaining({ name: 'Prøveblad', language: 'nb', source: 'wikidata' }),
    expect.objectContaining({ name: 'Δοκιμαστικό φύλλο', language: 'el', source: 'wikidata' }),
    expect.objectContaining({ name: 'Prøvespire', language: 'nb', source: 'nortaxa' }),
  ]));
  expect((await species()).names).not.toEqual(expect.arrayContaining([expect.objectContaining({ name: 'Ignored synthetic' })]));
});

test('stored scientific names keep exact-match priority over fuzzy vernacular neighbours', async () => {
  expect((await request(path + '/names', 'POST', { name: 'Testoria lucidaleaf', language: 'en' })).status).toBe(201);
  harness.setGbif({}, 503);
  for (const query of ['  TESTORIA LUCIDA  ', 'testoria lucida inventor']) {
    const result = await search(query);
    expect(result.candidates.find((row: { gbif_key: number }) => row.gbif_key === key)).toMatchObject({ requires_confirmation: false });
    expect(result.candidates.filter((row: { gbif_key: number }) => row.gbif_key === key)).toHaveLength(1);
    expect((await add(result)).status).toBe(201);
  }
});

test('a fuzzy vernacular neighbour does not hide an unstored scientific species from GBIF', async () => {
  expect((await request(path + '/names', 'POST', { name: 'Novoria testensis leaf', language: 'en' })).status).toBe(201);
  harness.setGbif({ usageKey: 910004, scientificName: 'Novoria testensis Inventor', canonicalName: 'Novoria testensis', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const result = await search('Novoria testensis');
  expect(result.candidates).toEqual(expect.arrayContaining([
    expect.objectContaining({ gbif_key: 910004, match_type: 'EXACT', requires_confirmation: false }),
    expect.objectContaining({ gbif_key: key, matched_name: 'Novoria testensis leaf', requires_confirmation: true }),
  ]));
});

test('stores the canonical Latin name and finds scientific-name typos', async () => {
  expect((await species()).names).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'Testoria lucida', language: 'la', source: 'gbif' }),
  ]));
  harness.setGbif({}, 503);
  const result = await search('Testori lucda');
  expect(result.candidates).toEqual(expect.arrayContaining([
    expect.objectContaining({ gbif_key: key, matched_name: 'Testoria lucida', matched_language: 'la', requires_confirmation: true }),
  ]));
  expect((await add(result)).status).toBe(422);
  expect((await add(result, key, { confirmed: true })).status).toBe(201);
});

test('searches English, Norwegian and Greek names, ignoring accents and tolerating Latin and Greek typos', async () => {
  harness.setGbif({}, 503);
  for (const [query, matched_name, matched_language, confirmation] of [
    ['TRIALLEAF', 'Trialleaf', 'en', false],
    ['  proveplante  ', 'Prøveplante', 'nb', false],
    ['ΔΟΚΙΜΟΦΥΤΟ', 'Δοκιμόφυτο', 'el', false],
    ['Triallef', 'Trialleaf', 'en', true],
    ['Δοκιμόφυτ', 'Δοκιμόφυτο', 'el', true],
    ['Δοκ', 'Δοκιμόφυτο', 'el', true],
    ['Prøvespire', 'Prøvespire', 'nb', false],
    ['Δοκιμαστικό φύλλο', 'Δοκιμαστικό φύλλο', 'el', false],
  ] as const) {
    const result = await search(query);
    expect(result.candidates[0]).toMatchObject({ gbif_key: key, matched_name, matched_language, requires_confirmation: confirmation });
    const added = await add(result, key, { requires_confirmation: false, match_type: 'EXACT', confidence: 100 });
    expect(added.status).toBe(confirmation ? 422 : 201);
    if (confirmation) expect((await add(result, key, { confirmed: true })).status).toBe(201);
  }
});

test('validates, adds and removes owner names, scoped to the selected species', async () => {
  for (const input of [{ name: '', language: 'en' }, { name: '  ', language: 'nb' },
    { name: 'x'.repeat(201), language: 'el' }, { name: 'Test name', language: 'fr' },
    { name: 42, language: 'en' }, { name: 'Bad\u0000name', language: 'en' }]) {
    expect((await request(path + '/names', 'POST', input)).status).toBe(422);
  }
  for (const input of [null, [], 'invalid']) expect((await request(path + '/names', 'POST', input)).status).toBe(400);
  const created = await request(path + '/names', 'POST', { name: '  Owner prøve  ', language: 'nb' });
  expect(created.status).toBe(201);
  const name = await created.json();
  expect(name).toMatchObject({ name: 'Owner prøve', language: 'nb', source: 'owner' });
  const duplicate = await (await request(path + '/names', 'POST', { name: 'Owner prove', language: 'nb' })).json();
  expect(duplicate.id).toBe(name.id);
  expect((await search('owner prove')).candidates[0]).toMatchObject({ gbif_key: key, requires_confirmation: false });
  expect((await request('/api/species/910099/names/' + name.id, 'DELETE')).status).toBe(404);
  const external = (await species()).names.find((item: { source: string }) => item.source === 'gbif');
  expect((await request(path + '/names/' + external.id, 'DELETE')).status).toBe(404);
  expect((await request(path + '/names/' + name.id, 'DELETE')).status).toBe(204);
  expect((await species()).names).not.toEqual(expect.arrayContaining([expect.objectContaining({ id: name.id })]));
  const literal = await (await request(path + '/names', 'POST', { name: '%Leaf', language: 'en' })).json();
  const prefix = await search('%');
  expect(prefix.candidates).toEqual([expect.objectContaining({ matched_name: '%Leaf', requires_confirmation: true })]);
  expect((await request(path + '/names/' + literal.id, 'DELETE')).status).toBe(204);
});

test('refreshes external names without removing owner names and skips ambiguous NorTaxa taxa', async () => {
  expect((await request(path + '/names', 'POST', { name: 'Owner keepsake', language: 'en' })).status).toBe(201);
  harness.setNames('gbif', { ...gbifNames, results: [{ vernacularName: 'Refreshed trialleaf', language: 'en' }] });
  harness.setNames('wikidata', wikiNames);
  harness.setNames('nortaxa', [norNames[0], { ...norNames[0], taxonId: 980002 }]);
  expect((await request(path + '/names/refresh', 'POST', {})).status).toBe(202);
  await expect.poll(async () => (await species()).name_fetch_needed).toBe(false);
  const names = (await species()).names;
  expect(names).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'Owner keepsake', source: 'owner' }),
    expect.objectContaining({ name: 'Refreshed trialleaf', source: 'gbif' }),
  ]));
  expect(names).not.toEqual(expect.arrayContaining([expect.objectContaining({ source: 'nortaxa' })]));
  expect(names).not.toEqual(expect.arrayContaining([expect.objectContaining({ name: 'Trialleaf', source: 'gbif' })]));
});

test('falls back to GBIF on a miss and requires confirmation for a name shared by two species', async () => {
  harness.setGbif({ usageKey: 910002, scientificName: 'Differentia testensis Inventor', canonicalName: 'Differentia testensis', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  harness.setNames('wikidata', { entities: { Q910002: { labels: {}, aliases: {} } } });
  const fallback = await search('Differentia testensis');
  expect(fallback.candidates[0]).toMatchObject({ gbif_key: 910002, match_type: 'EXACT' });
  expect((await add(fallback, 910002)).status).toBe(201);
  for (const speciesKey of [key, 910002]) {
    expect((await request(`/api/species/${speciesKey}/names`, 'POST', { name: 'Sharedleaf', language: 'en' })).status).toBe(201);
  }
  const ambiguous = await search('sharedleaf');
  expect(ambiguous.candidates).toEqual([
    expect.objectContaining({ gbif_key: 910002, matched_name: 'Sharedleaf', matched_language: 'en', requires_confirmation: true }),
    expect.objectContaining({ gbif_key: key, matched_name: 'Sharedleaf', matched_language: 'en', requires_confirmation: true }),
  ]);
  for (const speciesKey of [key, 910002]) {
    expect((await add(ambiguous, speciesKey, { confirmed: false, requires_confirmation: false, confidence: 100 })).status).toBe(422);
    expect((await add(ambiguous, speciesKey, { confirmed: true })).status).toBe(201);
  }
});

test('a timed-out source never fails plant add, other sources fill, and pending names retry without another add', async () => {
  harness.setGbif({ usageKey: 910003, scientificName: 'Failoria synthetic Inventor', canonicalName: 'Failoria synthetic', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  harness.setNames('gbif', {}, 503, 3500);
  harness.setNames('wikidata', { entities: { Q910003: { labels: { el: { language: 'el', value: 'Ανακτημένο δοκιμόφυτο' } }, aliases: {} } } });
  harness.setNames('nortaxa', []);
  const result = await search('Failoria synthetic');
  const start = performance.now();
  expect((await add(result, 910003)).status).toBe(201);
  expect(performance.now() - start).toBeLessThan(1000);
  const failedPath = '/api/species/910003';
  await expect.poll(async () => (await (await request(failedPath)).json()).names, { timeout: 15000 }).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'Ανακτημένο δοκιμόφυτο', source: 'wikidata' }),
  ]));
  await expect.poll(() => harness.appOutput, { timeout: 10000 }).toContain('Name fetch failed for gbif species 910003; will retry.');
  expect((await (await request(failedPath)).json()).name_fetch_needed).toBe(true);
  harness.setNames('gbif', { results: [{ vernacularName: 'Recovered leaf', language: 'en' }], endOfRecords: true });
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 60000 });
  await expect.poll(async () => (await (await request(failedPath)).json()).name_fetch_needed, { timeout: 15000 }).toBe(false);
  await request('/__test/clock', 'POST', { action: 'reset' });
  expect((await (await request(failedPath)).json()).names).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'Recovered leaf', source: 'gbif' }),
  ]));
}, 60000);

test('backs off only the failing source, caps failures, and lets the owner restart fetching', async () => {
  const clock = await (await request('/__test/clock')).json();
  await request('/__test/clock', 'POST', { action: 'set', at: clock.now });
  harness.setGbif({ usageKey: 910006, scientificName: 'Retryoria persistens Inventor', canonicalName: 'Retryoria persistens', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  harness.setNames('gbif', {}, 503);
  harness.setNames('wikidata', { entities: { Q910006: { labels: { el: { value: 'Δοκιμαστικό διαρκές φύλλο' } }, aliases: {} } } });
  harness.setNames('nortaxa', [{ taxonId: 980006, acceptedScientificName: { name: 'Retryoria persistens' }, preferredVernacularNames: [{ name: 'Varig prøveblad', nameLanguageIso: 'nb' }] }]);
  const result = await search('Retryoria persistens');
  expect((await add(result, 910006)).status).toBe(201);
  const retryPath = '/api/species/910006';
  const stored = [
    expect.objectContaining({ name: 'Retryoria persistens', language: 'la', source: 'gbif' }),
    expect.objectContaining({ name: 'Δοκιμαστικό διαρκές φύλλο', source: 'wikidata' }),
    expect.objectContaining({ name: 'Varig prøveblad', source: 'nortaxa' }),
  ];
  await expect.poll(async () => (await (await request(retryPath)).json()).names, { timeout: 15000 }).toEqual(expect.arrayContaining(stored));
  expect(harness.nameCalls('gbif', 910006)).toBe(1);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 59999 });
  await new Promise(resolve => setTimeout(resolve, 6000));
  expect(harness.nameCalls('gbif', 910006)).toBe(1);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 1 });
  await expect.poll(() => harness.nameCalls('gbif', 910006), { timeout: 10000 }).toBe(2);
  for (const [milliseconds, calls] of [[120000, 3], [240000, 4], [480000, 5], [960000, 6], [1920000, 7], [3840000, 8]]) {
    await expect.poll(() => harness.appOutput.match(/Name fetch failed for gbif species 910006/g)?.length ?? 0).toBe(calls - 1);
    await request('/__test/clock', 'POST', { action: 'advance', milliseconds });
    await expect.poll(() => harness.nameCalls('gbif', 910006), { timeout: 10000 }).toBe(calls);
  }
  await expect.poll(async () => (await (await request(retryPath)).json()).name_fetch_failed).toBe(true);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 172800000 });
  await new Promise(resolve => setTimeout(resolve, 6000));
  expect(harness.nameCalls('gbif', 910006)).toBe(8);
  expect(harness.nameCalls('wikidata', 910006)).toBe(1);
  expect(harness.nameCalls('nortaxa', 'Retryoria persistens')).toBe(1);
  const failed = await (await request(retryPath)).json();
  expect(failed).toMatchObject({ name_fetch_needed: false, name_fetch_failed: true });
  expect(failed.names).toEqual(expect.arrayContaining(stored));
  harness.setNames('gbif', { results: [{ vernacularName: 'Recovered trialleaf', language: 'en' }] });
  expect((await request(retryPath + '/names/refresh', 'POST', {})).status).toBe(202);
  await expect.poll(async () => (await (await request(retryPath)).json()).name_fetch_needed, { timeout: 15000 }).toBe(false);
  expect((await (await request(retryPath)).json()).name_fetch_failed).toBe(false);
  expect(harness.nameCalls('gbif', 910006)).toBe(9);
  await request('/__test/clock', 'POST', { action: 'reset' });
}, 90000);

test('an exact name-index hit for a lower taxon rank still requires confirmation', async () => {
  harness.setGbif({ usageKey: 910005, scientificName: 'Subrankia minima minor Inventor', canonicalName: 'Subrankia minima minor', rank: 'SUBSPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  harness.setNames('gbif', { results: [], endOfRecords: true });
  harness.setNames('wikidata', { entities: { Q910005: { labels: {}, aliases: {} } } });
  harness.setNames('nortaxa', []);
  const initial = await search('Subrankia minima minor');
  expect((await add(initial, 910005, { confirmed: true })).status).toBe(201);
  expect((await request('/api/species/910005/names', 'POST', { name: 'Rankleaf', language: 'en' })).status).toBe(201);
  harness.setGbif({}, 503);
  for (const query of ['rankleaf', 'Subrankia minima minor']) {
    const result = await search(query);
    expect(result.candidates.find((row: { gbif_key: number }) => row.gbif_key === 910005)).toMatchObject({ requires_confirmation: true });
    expect((await add(result, 910005)).status).toBe(422);
    expect((await add(result, 910005, { confirmed: true })).status).toBe(201);
  }
});

test('an exact stored scientific name ranks ahead of another species fuzzy vernacular hit', async () => {
  harness.setGbif({ usageKey: 910007, scientificName: 'Neighbouria synthetic Inventor', canonicalName: 'Neighbouria synthetic', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  harness.setNames('gbif', { results: [] });
  harness.setNames('wikidata', { entities: { Q910007: { labels: {}, aliases: {} } } });
  harness.setNames('nortaxa', []);
  expect((await add(await search('Neighbouria synthetic'), 910007)).status).toBe(201);
  await expect.poll(async () => (await (await request('/api/species/910007')).json()).name_fetch_needed, { timeout: 15000 }).toBe(false);
  expect((await request('/api/species/910007/names', 'POST', { name: 'Testoria lucida Inventor leaf', language: 'en' })).status).toBe(201);
  harness.setGbif({}, 503);
  const result = await search('Testoria lucida Inventor');
  expect(result.candidates[0]).toMatchObject({ gbif_key: key, requires_confirmation: false });
  expect(result.candidates).toEqual(expect.arrayContaining([
    expect.objectContaining({ gbif_key: 910007, matched_name: 'Testoria lucida Inventor leaf', requires_confirmation: true }),
  ]));
  expect((await add(result)).status).toBe(201);
});

test('refuses unauthenticated name reads and writes and cross-origin owner writes', async () => {
  for (const [url, method, body] of [[path, 'GET', undefined], [path + '/names', 'POST', { name: 'Test', language: 'en' }],
    [path + '/names/refresh', 'POST', {}], [path + '/names/00000000-0000-4000-8000-000000000000', 'DELETE', undefined],
    ['/api/species/search?q=Sharedleaf', 'GET', undefined]] as const) {
    expect((await request(url, method, body, '')).status).toBe(401);
  }
  const page = await request('/species/' + key, 'GET', undefined, '');
  expect(page.status).toBe(303);
  expect(page.headers.get('location')).toBe('/auth/login');
  for (const invalid of ['no-key', '0', '2147483648', '910099']) expect((await request('/api/species/' + invalid)).status).toBe(404);
  const { cookie } = await harness.login();
  const response = await fetch(harness.base + path + '/names', { method: 'POST', headers: { cookie, origin: 'https://foreign.example', 'content-type': 'application/json' }, body: JSON.stringify({ name: 'Test', language: 'en' }) });
  expect(response.status).toBe(403);
});

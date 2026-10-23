import { beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

let results = [{ pid: 'testoria_sensor', display_pid: 'Testoria sensor' }];
let status = 200;
let perenualPresent = false;
let calls = 0;
const harness = createApiHarness({ testClock: true, careEnv: { OPEN_PLANTBOOK_PER_MINUTE: '100' },
  perenual: path => ({ status: 200, body: path.endsWith('species-list')
    ? { data: perenualPresent ? [{ id: 2100, scientific_name: ['Testoria sensor'] }] : [] }
    : { sunlight: ['full sun'] } }),
  openPlantbook: (url, authorization) => {
    calls++;
    expect(authorization).toBe('Token synthetic-plantbook-token');
    if (url.pathname === '/api/v1/plant/search') {
      expect(url.searchParams.get('alias')).toBe('Testoria sensor');
      return { status, headers: status === 429 ? { 'retry-after': '120' } : undefined, body: { results } };
    }
    expect(url.pathname).toBe('/api/v1/plant/detail/testoria_sensor/');
    expect(url.searchParams.get('include')).toBe('care');
    return { status, body: { min_env_humid: 10, max_env_humid: 30, min_light_lux: 1000, max_light_lux: 3000, min_temp: 15, max_temp: 28, min_temp_survival: -5 } };
  },
});
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let plantId: string;
const card = () => request('/api/species/910100/care').then(r => r.json());
const tick = () => request('/__test/care-worker', 'POST', {});
const retry = () => request('/api/species/910100/care/retry-match', 'POST', {});
const exact = { usageKey: 910100, scientificName: 'Testoria sensor Inventor', canonicalName: 'Testoria sensor', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 };
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  ({ request } = await harness.login());
  const site = await (await request('/api/sites', 'POST', { name: 'Test sensor room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  harness.setGbif({ usageKey: 910100, scientificName: 'Testoria sensor Inventor', canonicalName: 'Testoria sensor', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const search = await (await request('/api/species/search?q=Testoria%20sensor')).json();
  const plant = await (await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910100 })).json();
  plantId = plant.id;
});

test('confirmed Plantbook care fills missing fields with source and fetch time', async () => {
  await tick();
  const care = await card();
  expect(care.openPlantbookMatch).toMatchObject({ status: 'matched', pid: 'testoria_sensor' });
  expect(care.profile.light).toEqual({ value: 'part_shade', source: 'open_plantbook', fetchedAt: '2030-06-01T12:00:00.000Z' });
  expect(care.profile.temperatureRangeC).toMatchObject({ value: { min: 15, max: 28 }, source: 'open_plantbook' });
  expect(care.profile.hardinessC).toMatchObject({ value: -5, source: 'open_plantbook' });
});

test('no match is reported on the card and owner list, then retry clears the old pid and matches again', async () => {
  results = [];
  expect((await retry()).status).toBe(202);
  await tick();
  expect((await card()).openPlantbookMatch).toMatchObject({ status: 'no_match', pid: null });
  expect(await (await request('/species/910100')).text()).toContain('Open Plantbook: no match');
  expect(await (await request('/api/species/unmatched')).json()).toContainEqual(expect.objectContaining({ gbif_key: 910100, status: 'no_match' }));
  results = [{ pid: 'testoria_sensor', display_pid: 'Testoria sensor' }];
  expect((await retry()).status).toBe(202);
  await tick();
  expect((await card()).openPlantbookMatch).toMatchObject({ status: 'matched', pid: 'testoria_sensor' });
  expect(await (await request('/api/species/unmatched')).json()).toEqual([]);
});

test.each([
  [{ ...exact, matchType: 'FUZZY' }, 'ambiguous'],
  [{ ...exact, confidence: 89 }, 'ambiguous'],
  [{ ...exact, rank: 'SUBSPECIES' }, 'ambiguous'],
  [{ ...exact, usageKey: 910999 }, 'no_match'],
  [{ ...exact, alternatives: [{ ...exact, usageKey: 910999 }] }, 'ambiguous'],
])('refuses non-confirmation-grade or different accepted keys %j', async (match, expected) => {
  harness.setGbif(match);
  await retry(); await tick();
  expect((await card()).openPlantbookMatch).toMatchObject({ status: expected, pid: null });
  expect((await card()).profile.light.source).toBe('unknown');
  harness.setGbif(exact);
});

test('two confirmed pids are ambiguous rather than picking the first', async () => {
  results = [{ pid: 'testoria_sensor', display_pid: 'Testoria sensor' }, { pid: 'testoria_second', display_pid: 'Testoria second' }];
  await retry(); await tick();
  expect((await card()).openPlantbookMatch).toMatchObject({ status: 'ambiguous', pid: null });
  expect(await (await request('/species/910100')).text()).toContain('Open Plantbook: ambiguous');
  results = [{ pid: 'testoria_sensor', display_pid: 'Testoria sensor' }];
});

test('Perenual outranks Plantbook per field, an override wins and survives refresh', async () => {
  perenualPresent = true;
  await request('/api/species/910100/care/refresh', 'POST', {}); await tick();
  expect((await card()).profile.light).toMatchObject({ value: 'full_sun', source: 'perenual' });
  await request('/api/species/910100/care/overrides/light', 'PUT', { value: 'full_shade' });
  await request('/api/species/910100/care/refresh', 'POST', {}); await tick();
  expect((await card()).profile.light).toMatchObject({ value: 'full_shade', source: 'override' });
  await request('/api/species/910100/care/overrides/light', 'DELETE');
  expect((await card()).profile.light.source).toBe('perenual');
  perenualPresent = false;
});

test.each([401, 403])('%s pauses only Plantbook until explicit retry without leaking its token', async failure => {
  status = failure;
  await retry();
  const before = calls;
  await tick();
  expect((await card()).fetches).toContainEqual(expect.objectContaining({ source: 'open_plantbook', status: 'auth_failed', attempts: 0 }));
  status = 200;
  await tick();
  expect(calls).toBe(before + 1);
  expect(harness.appOutput).not.toContain('synthetic-plantbook-token');
  expect(JSON.stringify(await card())).not.toContain('synthetic-plantbook-token');
  await retry(); await tick();
  expect((await card()).fetches).toContainEqual(expect.objectContaining({ source: 'open_plantbook', status: 'complete' }));
});

test('429 respects Retry-After with persisted backoff', async () => {
  status = 429;
  await retry();
  await tick();
  const care = await card();
  expect(care.fetches).toContainEqual(expect.objectContaining({ source: 'open_plantbook', status: 'pending', next_at: '2030-06-01T12:02:00.000Z' }));
  const before = calls;
  status = 200;
  await tick();
  expect(calls).toBe(before);
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 120000 });
  await tick();
  expect((await card()).fetches).toContainEqual(expect.objectContaining({ source: 'open_plantbook', status: 'complete' }));
});

test('match retries and unmatched list refuse unauthenticated, foreign-household and non-owner callers', async () => {
  for (const [path, method] of [['/api/species/910100/care/retry-match', 'POST'], ['/api/species/unmatched', 'GET'], ['/species/unmatched', 'GET']] as const) {
    const response = await request(path, method, undefined, '');
    expect(response.status).toBe(path.startsWith('/api/') ? 401 : 303);
    if (!path.startsWith('/api/')) expect(response.headers.get('location')).toBe('/auth/login');
  }
  const { cookie } = await harness.login();
  expect((await fetch(harness.base + '/api/species/910100/care/retry-match', { method: 'POST', headers: { cookie, origin: 'https://wrong.invalid' } })).status).toBe(403);
  const pool = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  try {
    await pool.query('DROP INDEX households_singleton');
    const other = await pool.query("INSERT INTO households (name) VALUES ('Test other sensor household') RETURNING id");
    await pool.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-sensor-other', 'owner' FROM members LIMIT 1", [other.rows[0].id]);
    const foreign = await harness.login('synthetic-sensor-other');
    expect((await foreign.request('/api/species/910100/care')).status).toBe(404);
    expect((await foreign.request('/api/species/910100/care/retry-match', 'POST')).status).toBe(404);
    results = []; await retry(); await tick();
    expect(await (await foreign.request('/api/species/unmatched')).json()).toEqual([]);
    expect(await (await request('/api/species/unmatched')).json()).toHaveLength(1);
    for (const role of ['family', 'sitter']) {
      await pool.query("INSERT INTO members (household_id, issuer, subject, role) SELECT household_id, issuer, $1, $2 FROM members WHERE subject = 'synthetic-owner' LIMIT 1", ['synthetic-sensor-' + role, role]);
      const member = await harness.login('synthetic-sensor-' + role);
      expect((await member.request('/api/species/910100/care/retry-match', 'POST')).status).toBe(role === 'family' ? 202 : 403);
      expect((await member.request('/api/species/unmatched')).status).toBe(role === 'family' ? 200 : 403);
    }
  } finally { await pool.end(); }
});

test('a GBIF synonym resolving to the same accepted species is matched', async () => {
  results = [{ pid: 'testoria_sensor', display_pid: 'Testoria sensor' }];
  harness.setGbif({ ...exact, usageKey: 910888, acceptedUsageKey: 910100, acceptedScientificName: 'Testoria sensor Inventor', acceptedCanonicalName: 'Testoria sensor' });
  await retry(); await tick();
  expect((await card()).openPlantbookMatch.status).toBe('matched');
  harness.setGbif(exact);
});

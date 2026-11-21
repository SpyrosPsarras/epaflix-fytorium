import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true, wiremock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let database: pg.Pool;

beforeAll(async () => {
  ({ request } = await harness.login());
  database = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  await database.query(`INSERT INTO species (gbif_key, scientific_name, canonical_name, rank, fetched_at) VALUES
    (930003, 'Testoria aranea Inventor', 'Testoria aranea', 'SPECIES', now()),
    (930004, 'Testoria serpens Inventor', 'Testoria serpens', 'SPECIES', now());
    INSERT INTO species_names (gbif_key, name, language, source) VALUES
    (930003, 'Spider Plant', 'en', 'owner'), (930004, 'Snake plant', 'en', 'owner');`);
});
afterAll(async () => { await database?.end(); });

const search = (query: string) => request('/api/species/search?' + new URLSearchParams({ q: query }));
async function clock(action: { action: 'set'; at: string } | { action: 'advance'; milliseconds: number }) {
  expect((await request('/__test/clock', 'POST', action)).status).toBe(200);
}
async function calls(path: string, parameter: string, value: string, agent?: string) {
  const response = await fetch(harness.wiremockUrl + '__admin/requests/count', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({
      method: 'GET', urlPath: path, queryParameters: { [parameter]: { equalTo: value } },
      ...(agent ? { headers: { 'User-Agent': { equalTo: agent } } } : {}),
    }),
  });
  expect(response.status).toBe(200);
  return (await response.json()).count;
}
const wikiCalls = (query: string, agent?: string) => calls('/w/api.php', 'srsearch', query + ' haswbstatement:P846', agent);

test('finds the accepted rubber plant through Wikidata without unrelated local names', async () => {
  const response = await search('rubber plant');
  expect(response.status).toBe(200);
  expect((await response.json()).candidates).toEqual([expect.objectContaining({ gbif_key: 930002,
    canonical_name: 'Ficus elastica', common_name: 'Rubber plant', matched_name: 'Rubber plant',
    matched_language: 'en', requires_confirmation: false })]);
  const userAgent = `Fytorium (+${harness.base})`;
  expect(await wikiCalls('rubber plant', userAgent)).toBe(1);
  expect(await calls('/species/match', 'name', 'rubber plant', userAgent)).toBe(1);
});

test('treats an exact Wikidata alias as exact despite case, accents and punctuation', async () => {
  expect((await (await search('Rúbber-plant')).json()).candidates).toEqual([
    expect.objectContaining({ gbif_key: 930002, matched_name: 'Rubber plant', requires_confirmation: false }),
  ]);
});

test.each([
  ['peace lily', 'Spathiphyllum floribundum', 3],
  ['fredslilje', 'Spathiphyllum wallisii', 3],
  ['monstera', 'Monstera deliciosa', 3],
  ['jade plant', 'Crassula ovata', 1],
  ['Crassula ovata', 'Crassula ovata', 1],
] as const)('ranks %s as expected in a fresh catalogue', async (query, expected, limit) => {
  const response = await search(query);
  expect(response.status).toBe(200);
  const { candidates } = await response.json();
  expect(candidates.length).toBeGreaterThan(1);
  expect(candidates.slice(0, limit)).toContainEqual(expect.objectContaining({ canonical_name: expected }));
});

test('requires confirmation when a Wikidata alias differs by a digit', async () => {
  const response = await search('Orchid 1');
  expect(response.status).toBe(200);
  expect((await response.json()).candidates).toEqual([expect.objectContaining({
    canonical_name: 'Phalaenopsis amabilis', common_name: 'Orchid 2', requires_confirmation: true,
  })]);
});

test('removes placeholders and doubtful taxa from GBIF matches and alternatives', async () => {
  const response = await search('sansevieria');
  expect(response.status).toBe(200);
  expect((await response.json()).candidates).toEqual([
    expect.objectContaining({ gbif_key: 930008, canonical_name: 'Dracaena trifasciata', requires_confirmation: true }),
  ]);
});

test('caches normalized remote results for seven days without freezing local names', async () => {
  await clock({ action: 'advance', milliseconds: 6 * 86400000 });
  await database.query("INSERT INTO species_names (gbif_key, name, language, source) VALUES (930003, 'Tall rubber planter', 'en', 'owner')");
  const response = await search('  RUBBER   PLANT ');
  expect(response.status).toBe(200);
  expect((await response.json()).candidates.map((row: { gbif_key: number }) => row.gbif_key)).toEqual([930002, 930003]);
  expect(await wikiCalls('rubber plant')).toBe(1);
  ({ request } = await harness.login());
  await clock({ action: 'advance', milliseconds: 2 * 86400000 });
  expect((await search('rubber plant')).status).toBe(200);
  expect(await wikiCalls('rubber plant')).toBe(2);
});

test('keeps GBIF results on Wikidata 429, skips Wikidata for 60 seconds, and does not cache the partial result', async () => {
  const quota = async () => {
    const response = await search('quota plant');
    expect(response.status).toBe(200);
    expect((await response.json()).candidates).toEqual([expect.objectContaining({ gbif_key: 930011 })]);
  };
  await quota();
  await clock({ action: 'advance', milliseconds: 59000 });
  await quota();
  expect(await wikiCalls('quota plant')).toBe(1);
  expect(await calls('/species/match', 'name', 'quota plant')).toBe(2);
  await clock({ action: 'advance', milliseconds: 1000 });
  await quota();
  expect(await wikiCalls('quota plant')).toBe(2);
});

test('honors a supplied Wikidata Retry-After shorter than the default', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  const query = 'timed quota plant';
  expect((await search(query)).status).toBe(200);
  expect(await wikiCalls(query)).toBe(1);
  await clock({ action: 'advance', milliseconds: 6000 });
  expect((await search(query)).status).toBe(200);
  expect(await wikiCalls(query)).toBe(1);
  await clock({ action: 'advance', milliseconds: 1000 });
  expect((await search(query)).status).toBe(200);
  expect(await wikiCalls(query)).toBe(2);
});

test('distinguishes no match from an outage', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  const outage = await search('sources down');
  expect(outage.status).toBe(503);
  expect(await outage.json()).toMatchObject({ message: 'Species search is unavailable. Try again in a minute.' });
  expect((await search('zzqxw')).status).toBe(422);
});

test('caches an empty remote result for one day and prunes expired queries on a cache write', async () => {
  expect(await wikiCalls('zzqxw')).toBe(1);
  expect((await search('zzqxw')).status).toBe(422);
  expect(await wikiCalls('zzqxw')).toBe(1);
  await clock({ action: 'advance', milliseconds: 86400000 });
  expect((await search('empty cache sweep')).status).toBe(422);
  expect((await database.query("SELECT 1 FROM species_search_cache WHERE query = 'zzqxw'")).rowCount).toBe(0);
  expect((await search('zzqxw')).status).toBe(422);
  expect(await wikiCalls('zzqxw')).toBe(2);
});

test('counts Wikidata HTTP requests, not searches, against the rolling minute limit', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  const searches = () => calls('/w/api.php', 'action', 'query');
  const entities = () => calls('/w/api.php', 'action', 'wbgetentities');
  const [searchesBefore, entitiesBefore] = await Promise.all([searches(), entities()]);
  expect((await search('rate limit test 0')).status).toBe(200);
  expect(await entities()).toBe(entitiesBefore + 1);
  for (let index = 1; index < 21; index++) {
    const query = `rate limit test ${index}`;
    expect((await search(query)).status).toBe(422);
    expect(await wikiCalls(query)).toBe(index < 19 ? 1 : 0);
  }
  expect(await searches()).toBe(searchesBefore + 19);
  expect(await entities()).toBe(entitiesBefore + 1);
  await clock({ action: 'advance', milliseconds: 60000 });
  expect((await search('rate limit test 20')).status).toBe(422);
  expect(await wikiCalls('rate limit test 20')).toBe(1);
});

test('requires confirmation for a stored and Wikidata alias that differ only in punctuation', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  await database.query("INSERT INTO species_names (gbif_key, name, language, source) VALUES (930003, 'Rubber-plant', 'en', 'owner')");
  try {
    const response = await search('rubber plant');
    expect(response.status).toBe(200);
    expect((await response.json()).candidates).toEqual([
      expect.objectContaining({ gbif_key: 930003, matched_name: 'Rubber-plant', requires_confirmation: true }),
      expect.objectContaining({ gbif_key: 930002, matched_name: 'Rubber plant', requires_confirmation: true }),
    ]);
  } finally {
    await database.query("DELETE FROM species_names WHERE gbif_key = 930003 AND name = 'Rubber-plant' AND source = 'owner'");
  }
});

test.each([
  ['ladys slipper', "Lady's slipper", 'Ladys slipper'],
  ['queens slipper', 'Queen’s slipper', 'Queens slipper'],
  ['silverleaf', 'Silver-leaf', 'Silverleaf'],
  ['forkleaf', 'Fork/leaf', 'Forkleaf'],
  ['Pilea peperomioides', 'Pilea péperomioides', 'Pilea peperomioides'],
  ['  fern   star ', 'Fern    star', 'Fern star'],
  ['MOON VINE', 'Moon Vine', 'Moon vine'],
  ['Test orchid 1', 'Test Orchid 1', 'Test orchid 1'],
  ['Test orchid 2', 'Test Orchid 2', 'Test orchid 2'],
  ['proveblad', 'Prøveblad', 'Proveblad'],
  ['vaerblad', 'Værblad', 'Vaerblad'],
  ['ΦΩΣ ΦΥΤΟ', 'Φως φυτό', 'Φωσ φυτο'],
] as const)('offers both exact species with confirmation for %s', async (query, stored, remote) => {
  await clock({ action: 'advance', milliseconds: 60000 });
  await database.query("INSERT INTO species_names (gbif_key, name, language, source) VALUES (930003, $1, 'en', 'owner')", [stored]);
  try {
    const response = await search(query);
    expect(response.status).toBe(200);
    expect((await response.json()).candidates).toEqual([
      expect.objectContaining({ gbif_key: 930003, matched_name: stored, requires_confirmation: true }),
      expect.objectContaining({ gbif_key: 930002, matched_name: remote, requires_confirmation: true }),
    ]);
  } finally {
    await database.query("DELETE FROM species_names WHERE gbif_key = 930003 AND name = $1 AND source = 'owner'", [stored]);
  }
});

test('an unrelated GBIF match cannot bypass confirmation when exact names collide', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  await database.query("INSERT INTO species_names (gbif_key, name, language, source) VALUES (930003, 'Twinned leaf', 'en', 'owner'), (930004, 'Twinned leaf', 'en', 'owner')");
  try {
    const response = await search('twinned leaf');
    expect(response.status).toBe(200);
    expect((await response.json()).candidates).toEqual([930003, 930004, 930052].map(gbif_key =>
      expect.objectContaining({ gbif_key, requires_confirmation: true })));
  } finally {
    await database.query("DELETE FROM species_names WHERE name = 'Twinned leaf' AND gbif_key IN (930003, 930004) AND source = 'owner'");
  }
});

test('counts a GBIF synonym even when an earlier match has the same accepted key', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  await database.query("UPDATE species SET scientific_name = 'Testoria vetus Inventor' WHERE gbif_key = 930004");
  try {
    const response = await search('Testoria vetus Inventor');
    expect(response.status).toBe(200);
    expect((await response.json()).candidates).toEqual([
      expect.objectContaining({ gbif_key: 930004, requires_confirmation: true }),
      expect.objectContaining({ gbif_key: 930052, canonical_name: 'Testoria remota', requires_confirmation: true }),
    ]);
  } finally {
    await database.query("UPDATE species SET scientific_name = 'Testoria serpens Inventor' WHERE gbif_key = 930004");
  }
});

test('counts the GBIF name behind a Wikidata synonym even if Wikidata does not repeat it', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  await database.query("UPDATE species SET canonical_name = 'Ficus ficta' WHERE gbif_key = 930004");
  try {
    const response = await search('Ficus ficta');
    expect(response.status).toBe(200);
    expect((await response.json()).candidates).toEqual([930004, 930002].map(gbif_key =>
      expect.objectContaining({ gbif_key, requires_confirmation: true })));
  } finally {
    await database.query("UPDATE species SET canonical_name = 'Testoria serpens' WHERE gbif_key = 930004");
  }
});

test('includes a GBIF alternative after NONE and requires confirmation for both exact species', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  const response = await search('Testoria serpens');
  expect(response.status).toBe(200);
  expect((await response.json()).candidates).toEqual([
    expect.objectContaining({ gbif_key: 930004, requires_confirmation: true }),
    expect.objectContaining({ gbif_key: 930054, match_type: 'EXACT', requires_confirmation: true }),
  ]);
});

test('refreshes two expired cache keys concurrently without a deadlock', async () => {
  await clock({ action: 'advance', milliseconds: 60000 });
  await database.query(`INSERT INTO species_search_cache (query, candidates, expires_at) VALUES
    ('peace lily', '[]', '-infinity'), ('monstera', '[]', '-infinity')
    ON CONFLICT (query) DO UPDATE SET expires_at = EXCLUDED.expires_at;
    CREATE FUNCTION hold_cache_refresh() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN PERFORM pg_advisory_xact_lock_shared(940001); RETURN NEW; END $$;
    CREATE TRIGGER hold_cache_refresh BEFORE UPDATE ON species_search_cache
    FOR EACH ROW WHEN (OLD.query IN ('peace lily', 'monstera')) EXECUTE FUNCTION hold_cache_refresh();`);
  const gate = await database.connect();
  try {
    await gate.query('SELECT pg_advisory_lock(940001)');
    const searches = Promise.allSettled([search('peace lily'), search('monstera')]);
    try {
      await expect.poll(async () => (await database.query(`SELECT count(*)::int AS count FROM pg_locks
        WHERE locktype = 'advisory' AND objid = 940001 AND NOT granted`)).rows[0].count, { timeout: 10000 }).toBe(2);
    } finally {
      await gate.query('SELECT pg_advisory_unlock(940001)');
      await searches;
    }
    expect((await searches).map(result => result.status === 'fulfilled' ? result.value.status : result.reason.message)).toEqual([200, 200]);
    expect((await database.query("SELECT query FROM species_search_cache WHERE query IN ('monstera', 'peace lily') AND expires_at > '-infinity' ORDER BY query")).rows)
      .toEqual([{ query: 'monstera' }, { query: 'peace lily' }]);
  } finally {
    gate.release();
    await database.query('DROP TRIGGER hold_cache_refresh ON species_search_cache; DROP FUNCTION hold_cache_refresh()');
  }
});

test('the search cache migration rolls back and reapplies', async () => {
  const [up, down] = readFileSync('migrations/20261120090000_species_search_cache.sql', 'utf8').split('-- migrate:down');
  const client = await database.connect();
  try {
    await client.query('BEGIN');
    await client.query(down);
    expect((await client.query("SELECT to_regclass('species_search_cache') AS name")).rows[0].name).toBeNull();
    await client.query(up);
    await client.query("INSERT INTO species_search_cache (query, candidates, expires_at) VALUES ('synthetic migration', '[]', now())");
    expect((await client.query('SELECT query, candidates FROM species_search_cache')).rows).toEqual([{ query: 'synthetic migration', candidates: [] }]);
  } finally {
    await client.query('ROLLBACK');
    client.release();
  }
});

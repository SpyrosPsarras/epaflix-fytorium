import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { fixturePlant } from '../plant-fixture';
import { createApiHarness } from './harness';
import { createFakePlanta, syntheticPlant } from './planta-fake';

const fake = createFakePlanta();
fake.env.HOUSEHOLD_TIMEZONE = 'America/Los_Angeles';
const harness = createApiHarness({ testClock: true, appEnv: fake.env });
let owner: Awaited<ReturnType<typeof harness.login>>;
let database: pg.Pool;
beforeAll(async () => {
  owner = await harness.login();
  database = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-10T02:00:00Z' });
});
afterAll(async () => { await database?.end(); });

test('date-only care is a household calendar date, including the current household day', async () => {
  const plant = syntheticPlant('synthetic-local-date');
  plant.actions.watering.completed.date = '2026-06-09';
  fake.setPlants([plant]);
  expect((await owner.request('/api/import/planta', 'POST', { code: fake.code })).status).toBe(200);
  const committed = await owner.request('/api/import/planta/commit', 'POST', {});
  expect(committed.status).toBe(200);
  const row = (await committed.json())[0];
  const history = await (await owner.request('/api/plants/' + row.plant_id + '/history')).json();
  expect(history).toContainEqual(expect.objectContaining({ task_type: 'water', occurred_on: '2026-06-09', occurred_at: '2026-06-09T07:00:00.000Z', due_on: '2026-06-09' }));
  plant.actions.watering.completed.date = '2026-06-10';
  fake.setPlants([plant]);
  expect((await owner.request('/api/import/planta', 'POST', { code: fake.code })).status).toBe(422);
});

test('import leaves plant attributes that Planta did not supply unset', async () => {
  const { environment: _environment, ...plant } = syntheticPlant('synthetic-unset-attributes');
  fake.setPlants([plant]);
  const fetched = await owner.request('/api/import/planta', 'POST', { code: fake.code });
  expect(fetched.status).toBe(200);
  const committed = await owner.request('/api/import/planta/commit', 'POST', {});
  expect(committed.status).toBe(200);
  const [row] = await committed.json();
  const stored = await owner.request('/api/plants/' + row.plant_id);
  expect(stored.status).toBe(200);
  expect(await stored.json()).toMatchObject({ pot_type: null, soil_type: null, plant_size: null, growth_stage: null, fertilizer_type: null, drainage: null });
});

test('adding a plant with initial watering and committing a Planta import can save the same species concurrently', async () => {
  harness.setGbifSpecies(910001, { key: 910001, scientificName: 'Testoria lucida Inventor', canonicalName: 'Testoria lucida', rank: 'SPECIES', kingdom: 'Plantae', taxonomicStatus: 'ACCEPTED' });
  fake.setPlants([syntheticPlant('synthetic-concurrent-import')]);
  const staged = await owner.request('/api/import/planta', 'POST', { code: fake.code });
  expect(staged.status).toBe(200);
  const [row] = await staged.json();
  expect(row).toMatchObject({ status: 'confirmed', selected: { gbif_key: 910001 } });
  const site = await owner.request('/api/sites', 'POST', { name: 'Concurrent Add site', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false });
  expect(site.status).toBe(201);
  const { id: site_id } = await site.json();
  await database.query(`CREATE FUNCTION hold_plant_add() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN PERFORM pg_advisory_xact_lock_shared(940002); RETURN NEW; END $$;
    CREATE TRIGGER hold_plant_add BEFORE INSERT ON plants
    FOR EACH ROW WHEN (NEW.nickname = 'Concurrent Add') EXECUTE FUNCTION hold_plant_add();`);
  const gate = await database.connect();
  const status = (response: Promise<Response>) => response.then(result => result.status, (error: Error) => error.message);
  const results: Promise<number | string>[] = [];
  try {
    await gate.query('SELECT pg_advisory_lock(940002)');
    try {
      results.push(status(owner.request('/api/plants', 'POST', { ...fixturePlant, site_id, nickname: 'Concurrent Add', gbif_key: 910001, confirmed: true, last_watered_on: '2026-06-09' })));
      await expect.poll(async () => (await database.query(`SELECT 1 FROM pg_locks
        WHERE locktype = 'advisory' AND objid = 940002 AND NOT granted`)).rowCount, { timeout: 10000 }).toBe(1);
      results.push(status(owner.request('/api/import/planta/commit', 'POST', {})));
      await expect.poll(async () => (await database.query(`SELECT 1 FROM pg_stat_activity
        WHERE wait_event_type = 'Lock' AND (query LIKE 'SELECT scientific_name, canonical_name FROM species%FOR UPDATE'
          OR query LIKE 'INSERT INTO species (%' OR query LIKE 'INSERT INTO care_fetch_jobs (%')`)).rowCount, { timeout: 10000 }).toBe(1);
    } finally {
      await gate.query('SELECT pg_advisory_unlock(940002)');
      await Promise.all(results);
    }
    expect(await Promise.all(results)).toEqual([201, 200]);
  } finally {
    gate.release();
    await database.query('DROP TRIGGER hold_plant_add ON plants; DROP FUNCTION hold_plant_add()');
  }
});

test('commits staged Planta rows after rollback with the original import fallbacks', async () => {
  const { environment: _environment, ...unset } = syntheticPlant('synthetic-rollback-unset');
  const partial = { ...syntheticPlant('synthetic-rollback-partial'), environment: { pot: { type: 'potterracotta', size: 18 } } };
  fake.setPlants([unset, partial]);
  const staged = await owner.request('/api/import/planta', 'POST', { code: fake.code });
  expect(staged.status).toBe(200);
  expect(await staged.json()).toEqual([
    expect.objectContaining({ planta_plant_id: partial.id, status: 'confirmed', mapped: expect.objectContaining({ pot_type: 'terracotta', soil_type: null }) }),
    expect.objectContaining({ planta_plant_id: unset.id, status: 'confirmed', mapped: expect.objectContaining({ pot_type: null, soil_type: null }) }),
  ]);
  const [up, down] = readFileSync('migrations/20261120100000_nullable_plant_attributes.sql', 'utf8').split('-- migrate:down');
  await database.query(down);
  try {
    const committed = await owner.request('/api/import/planta/commit', 'POST', {});
    expect(committed.status).toBe(200);
    const rows = await committed.json();
    for (const [id, pot_type, pot_size_cm] of [[unset.id, 'other', null], [partial.id, 'terracotta', 18]] as const) {
      const row = rows.find((item: { planta_plant_id: string }) => item.planta_plant_id === id);
      expect(row).toMatchObject({ status: 'committed', mapped: { pot_type, pot_size_cm, soil_type: 'other' } });
      const plant = await owner.request('/api/plants/' + row.plant_id);
      expect(plant.status).toBe(200);
      expect(await plant.json()).toMatchObject({ pot_type, pot_size_cm, soil_type: 'other', plant_size: 'small', growth_stage: 'young', fertilizer_type: 'liquid', drainage: false });
    }
  } finally {
    await database.query(up);
  }
});

import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
const dates = ['2030-05-30', '2030-05-31', '2030-06-01', '2030-06-02', '2030-06-03', '2030-06-04', '2030-06-05', '2030-06-06', '2030-06-07'];
const harness = createApiHarness({ testClock: true, weather: url => ({ status: 200, body: url.pathname === '/v1/forecast'
  ? { daily: { time: dates, temperature_2m_min: dates.map(() => 15), temperature_2m_max: dates.map(() => 20), precipitation_sum: dates.map(() => 6), daylight_duration: dates.map(() => 43200) } }
  : { daily: { time: ['2000-01-01'], temperature_2m_min: [5] } } }) });
async function setup(location: 'indoor' | 'outdoor', count: number) {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  const { request } = await harness.login();
  const site = await (await request('/api/sites', 'POST', { name: 'Synthetic batch site ' + randomUUID(), location, light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const plants = [];
  for (let i = 0; i < count; i++) {
    const response = await request('/api/plants', 'POST', { ...fixturePlant, nickname: 'Synthetic batch plant ' + randomUUID(), site_id: site.id, search_id: search.search_id, gbif_key: 910001 });
    expect(response.status).toBe(201);
    plants.push(await response.json());
  }
  return { request, plants };
}
test('full deletion after complete-all keeps the other plant and its care events intact', async () => {
  const { request, plants: [deleted, kept] } = await setup('indoor', 2);
  const complete = (id: string) => request(`/api/plants/${id}/tasks/complete-all`, 'POST', { request_id: randomUUID() });
  expect((await complete(deleted.id)).status).toBe(200);
  expect((await complete(kept.id)).status).toBe(200);
  const keptHistory = await (await request(`/api/plants/${kept.id}/history`)).json();
  expect(keptHistory.length).toBeGreaterThan(0);
  expect((await request(`/api/plants/${deleted.id}`, 'DELETE', { confirm_name: deleted.display_name })).status).toBe(204);
  for (const suffix of ['', '/history']) expect((await request(`/api/plants/${deleted.id}` + suffix)).status).toBe(404);
  expect(await (await request(`/api/plants/${kept.id}`)).json()).toEqual(kept);
  expect(await (await request(`/api/plants/${kept.id}/history`)).json()).toEqual(keptHistory);
});
test('full deletion after rain spanning three plants keeps all surviving plants and their batch events', async () => {
  const { request, plants } = await setup('outdoor', 3);
  expect((await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } })).status).toBe(200);
  expect((await request('/__test/weather-worker', 'POST')).status).toBe(200);
  const original = randomUUID();
  const response = await request('/api/weather/rain/water', 'POST', { request_id: original });
  expect(response.status).toBe(200);
  const batch = await response.json();
  expect(batch).toEqual({ batchId: original, plantCount: 3, undoneAt: null });
  const reused = await request('/api/weather/rain/water', 'POST', { request_id: randomUUID() });
  expect(reused.status).toBe(200);
  expect(await reused.json()).toEqual(batch);
  const [deleted, ...kept] = plants;
  const histories = await Promise.all(kept.map(plant => request(`/api/plants/${plant.id}/history`).then(r => r.json())));
  for (const history of histories) expect(history).toContainEqual(expect.objectContaining({ batch_id: batch.batchId, kind: 'completed' }));
  expect((await request(`/api/plants/${deleted.id}`, 'DELETE', { confirm_name: deleted.display_name })).status).toBe(204);
  expect((await request(`/api/plants/${deleted.id}/history`)).status).toBe(404);
  for (const [i, plant] of kept.entries()) {
    expect(await (await request(`/api/plants/${plant.id}`)).json()).toEqual(plant);
    expect(await (await request(`/api/plants/${plant.id}/history`)).json()).toEqual(histories[i]);
  }
  expect((await request(`/api/task-batches/${batch.batchId}/undo`, 'POST')).status).toBe(200);
});
test('care action integrity rejects foreign plants and rain aliases targeting a non-rain action', async () => {
  const { request, plants: [plant] } = await setup('indoor', 1);
  const key = randomUUID();
  expect((await request(`/api/plants/${plant.id}/tasks/complete-all`, 'POST', { request_id: key })).status).toBe(200);
  const history = await (await request(`/api/plants/${plant.id}/history`)).json();
  const db = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await db.connect();
  let foreign: string | undefined;
  let dropped = false;
  try {
    await db.query('DROP INDEX households_singleton');
    dropped = true;
    foreign = (await db.query("INSERT INTO households (name) VALUES ('Synthetic foreign batch household') RETURNING id")).rows[0].id;
    await expect(db.query("INSERT INTO care_actions (request_id, household_id, plant_id, action) VALUES ($1,$2,$3,'complete-all')", [randomUUID(), foreign, plant.id])).rejects.toMatchObject({ code: '23503' });
    await expect(db.query("INSERT INTO care_actions (request_id, household_id, action, batch_id) VALUES ($1,$2,'rain',$3)", [randomUUID(), plant.household_id, key])).rejects.toMatchObject({ code: '23503', constraint: 'care_actions_rain_alias_target' });
    expect((await request('/api/weather/rain/water', 'POST', { request_id: key })).status).toBe(409);
    expect(await (await request(`/api/plants/${plant.id}/history`)).json()).toEqual(history);
  } finally {
    try { if (foreign) await db.query('DELETE FROM households WHERE id = $1', [foreign]); }
    finally {
      try { if (dropped) await db.query('CREATE UNIQUE INDEX households_singleton ON households ((true))'); }
      finally { await db.end(); }
    }
  }
});
test('complete-type migration preserves plant scoping and refuses rollback over retained batches', async () => {
  const { plants: [plant] } = await setup('indoor', 1);
  const db = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await db.connect();
  try {
    for (const [action, plantId] of [['complete-type', plant.id], ['complete-all', null], ['rain', plant.id]] as const) {
      await expect(db.query('INSERT INTO care_actions (request_id, household_id, plant_id, action) VALUES ($1,$2,$3,$4)', [randomUUID(), plant.household_id, plantId, action]))
        .rejects.toMatchObject({ code: '23514', constraint: 'care_actions_action_plant' });
    }
    await db.query('BEGIN');
    await db.query("INSERT INTO care_actions (request_id, household_id, action) VALUES ($1,$2,'complete-type')", [randomUUID(), plant.household_id]);
    const rollback = (await readFile('migrations/20261020100000_complete_type.sql', 'utf8')).split('-- migrate:down')[1];
    await expect(db.query(rollback)).rejects.toMatchObject({ code: '23514', constraint: 'care_actions_action_plant' });
  } finally { await db.query('ROLLBACK'); await db.end(); }
});

test('multi-row inserts reject aliases before non-rain targets but accept original rain targets', async () => {
  const { plants: [plant] } = await setup('indoor', 1);
  const target = randomUUID(), alias = randomUUID();
  const db = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await db.connect();
  try {
    await expect(db.query(`INSERT INTO care_actions (request_id, household_id, plant_id, action, batch_id)
      VALUES ($1,$3,NULL,'rain',$2),($2,$3,$4,'complete-all',NULL)`, [alias, target, plant.household_id, plant.id]))
      .rejects.toMatchObject({ code: '23503', constraint: 'care_actions_rain_alias_target' });
    expect((await db.query('SELECT request_id FROM care_actions WHERE request_id = ANY($1::uuid[])', [[alias, target]])).rows).toEqual([]);
    await db.query(`INSERT INTO care_actions (request_id, household_id, action, batch_id)
      VALUES ($1,$3,'rain',$2),($2,$3,'rain',NULL)`, [alias, target, plant.household_id]);
    expect((await db.query('SELECT request_id FROM care_actions WHERE request_id = ANY($1::uuid[])', [[alias, target]])).rowCount).toBe(2);
    expect((await db.query('DELETE FROM care_actions WHERE request_id = ANY($1::uuid[])', [[target, alias]])).rowCount).toBe(2);
  } finally { await db.end(); }
});
test('referenced rain actions reject action and batch updates and deletion but still allow undo', async () => {
  const { plants: [plant] } = await setup('indoor', 1);
  const original = randomUUID(), other = randomUUID(), alias = randomUUID();
  const db = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await db.connect();
  try {
    await db.query("INSERT INTO care_actions (request_id, household_id, action) VALUES ($1,$3,'rain'),($2,$3,'rain')", [original, other, plant.household_id]);
    await db.query("INSERT INTO care_actions (request_id, household_id, action, batch_id) VALUES ($1,$2,'rain',$3)", [alias, plant.household_id, original]);
    await expect(db.query("UPDATE care_actions SET action = 'complete-all', plant_id = $2 WHERE request_id = $1", [original, plant.id])).rejects.toMatchObject({ code: '23503', constraint: 'care_actions_rain_alias_target' });
    await expect(db.query('UPDATE care_actions SET batch_id = $2 WHERE request_id = $1', [original, other])).rejects.toMatchObject({ code: '23503', constraint: 'care_actions_rain_alias_target' });
    await expect(db.query('DELETE FROM care_actions WHERE request_id = $1', [original])).rejects.toMatchObject({ code: '23503' });
    expect((await db.query('SELECT action, batch_id FROM care_actions WHERE request_id = $1', [original])).rows[0]).toEqual({ action: 'rain', batch_id: null });
    await db.query('UPDATE care_actions SET undone_at = now() WHERE request_id = $1', [original]);
    await db.query('DELETE FROM care_actions WHERE request_id = $1', [alias]);
    await db.query("UPDATE care_actions SET action = 'complete-all', plant_id = $2 WHERE request_id = $1", [original, plant.id]);
  } finally { await db.end(); }
});
test('migration rejects an existing invalid rain alias without changing the schema', async () => {
  const { plants: [plant] } = await setup('indoor', 1);
  const target = randomUUID(), alias = randomUUID();
  const [migration, rollback] = (await readFile('migrations/20261012220000_rain_alias_integrity.sql', 'utf8')).split('-- migrate:down');
  const db = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await db.connect();
  try {
    await db.query('BEGIN');
    await db.query(rollback);
    await db.query("INSERT INTO care_actions (request_id, household_id, plant_id, action) VALUES ($1,$2,$3,'complete-all')", [target, plant.household_id, plant.id]);
    await db.query("INSERT INTO care_actions (request_id, household_id, action, batch_id) VALUES ($1,$2,'rain',$3)", [alias, plant.household_id, target]);
    await expect(db.query(migration)).rejects.toMatchObject({
      code: '23514', constraint: 'care_actions_rain_alias_target',
      message: 'Existing rain aliases must reference original rain actions in their household',
      hint: expect.stringContaining('SELECT alias.* FROM care_actions alias LEFT JOIN care_actions target')
    });
  } finally { await db.query('ROLLBACK'); await db.end(); }
  const check = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await check.connect();
  try {
    expect((await check.query('SELECT request_id FROM care_actions WHERE request_id = ANY($1::uuid[])', [[alias, target]])).rows).toEqual([]);
    await expect(check.query("INSERT INTO care_actions (request_id, household_id, action, batch_id) VALUES ($1,$2,'rain',$3)", [alias, plant.household_id, target])).rejects.toMatchObject({ code: '23503' });
  } finally { await check.end(); }
});
test('an invalid rain alias target maps to 422 and rolls back the API request', async () => {
  const { request, plants: [plant] } = await setup('outdoor', 1);
  const target = randomUUID(), alias = randomUUID();
  expect((await request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } })).status).toBe(200);
  expect((await request('/__test/weather-worker', 'POST')).status).toBe(200);
  expect((await request('/api/plants/' + plant.id + '/tasks/complete-all', 'POST', { request_id: target })).status).toBe(200);
  const db = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await db.connect();
  const previous = await db.query("SELECT id, batch_id FROM weather_events WHERE household_id = $1 AND kind = 'rain' AND resolved_at IS NULL", [plant.household_id]);
  try {
    expect(previous.rowCount).toBeGreaterThan(0);
    await db.query('UPDATE weather_events SET batch_id = $1 WHERE id = ANY($2::uuid[])', [target, previous.rows.map(row => row.id)]);
    const response = await request('/api/weather/rain/water', 'POST', { request_id: alias });
    expect(response.status).toBe(422);
    expect((await response.json()).errors._form).toBe('A rain alias must reference an original rain action in its household.');
    expect((await db.query('SELECT request_id FROM care_actions WHERE request_id = $1', [alias])).rows).toEqual([]);
  } finally {
    for (const row of previous.rows) await db.query('UPDATE weather_events SET batch_id = $1 WHERE id = $2', [row.batch_id, row.id]);
    await db.end();
  }
});

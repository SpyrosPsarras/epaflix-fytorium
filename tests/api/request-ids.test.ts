import { randomUUID } from 'node:crypto';
import { expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness();

test('care-event request ids are household-scoped while cross-household command reuse returns 409', async () => {
  const owner = await harness.login();
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Synthetic request room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  const plant = await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json();
  const requestId = randomUUID();
  const done = await owner.request(`/api/plants/${plant.id}/tasks/water/complete`, 'POST', { request_id: requestId });
  expect(done.status).toBe(200);
  const completion = await done.json();
  const history = await (await owner.request(`/api/plants/${plant.id}/history`)).json();
  expect(history).toHaveLength(1);
  const database = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await database.connect();
  let foreign: string | undefined;
  let dropped = false;
  try {
    await database.query('DROP INDEX households_singleton');
    dropped = true;
    foreign = (await database.query("INSERT INTO households (name) VALUES ('Synthetic request household') RETURNING id")).rows[0].id;
    await database.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-request-other', 'owner' FROM members LIMIT 1", [foreign]);
    const foreignSite = (await database.query(`INSERT INTO sites (household_id, name, location, light, humidity, grow_light, draft)
      VALUES ($1, 'Synthetic foreign room', 'indoor', 'part_sun', 'normal', false, false) RETURNING id`, [foreign])).rows[0].id;
    const foreignPlant = (await database.query(`INSERT INTO plants (household_id, site_id, gbif_key, nickname)
      VALUES ($1, $2, 910001, 'Synthetic foreign fern') RETURNING id`, [foreign, foreignSite])).rows[0].id;
    const other = await harness.login('synthetic-request-other');
    // A standalone event can reuse the UUID in another household, without a care action.
    await database.query(`INSERT INTO care_events (household_id, plant_id, task_type, kind, occurred_at, by_name, request_id)
      VALUES ($1, $2, 'water', 'completed', now(), 'Synthetic foreign member', $3)`, [foreign, foreignPlant, requestId]);
    const refused = await other.request(`/api/plants/${foreignPlant}/tasks/water/complete`, 'POST', { request_id: requestId });
    expect(refused.status).toBe(409);
    expect(await refused.json()).toMatchObject({ message: 'request_id already used for another action.' });
    const retried = await owner.request(`/api/plants/${plant.id}/tasks/water/complete`, 'POST', { request_id: requestId });
    expect(retried.status).toBe(200);
    expect((await retried.json()).event.id).toBe(completion.event.id);
    expect(await (await owner.request(`/api/plants/${plant.id}/history`)).json()).toEqual(history);
  } finally {
    try {
      if (foreign) {
        await database.query('DELETE FROM plants WHERE household_id = $1', [foreign]);
        await database.query('DELETE FROM sites WHERE household_id = $1', [foreign]);
        await database.query('DELETE FROM members WHERE household_id = $1', [foreign]);
        await database.query('DELETE FROM households WHERE id = $1', [foreign]);
      }
    } finally {
      try { if (dropped) await database.query('CREATE UNIQUE INDEX households_singleton ON households ((true))'); }
      finally { await database.end(); }
    }
  }
});

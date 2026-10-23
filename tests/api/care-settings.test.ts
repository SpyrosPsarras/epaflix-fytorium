import { afterAll, beforeAll, expect, test } from 'vitest';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let plantId: string;
let fixtureDb: pg.Pool | undefined;

beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2026-05-01T12:00:00Z' }) });
  ({ request } = await harness.login());
  const site = await (await request('/api/sites', 'POST', { name: 'Test care room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const added = await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 });
  expect(added.status).toBe(201);
  plantId = (await added.json()).id;
});
afterAll(async () => { if (fixtureDb) await fixtureDb.end(); });
const tasks = () => request('/api/plants/' + plantId + '/tasks').then(response => response.json());
const plantSettings = () => '/api/plants/' + plantId + '/care-settings';

test('completes and undoes leaf cleaning through the shared care history', async () => {
  expect(await tasks()).toMatchObject({ clean_leaves: { dueOn: '2026-05-31', intervalDays: 30 } });
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 86400000 });
  const done = await request('/api/plants/' + plantId + '/tasks/clean_leaves/complete', 'POST', { request_id: randomUUID() });
  expect(done.status).toBe(200);
  const result = await done.json();
  expect(result.task).toMatchObject({ dueOn: '2026-06-01', intervalDays: 30 });
  expect((await (await request('/api/plants/' + plantId + '/history')).json())[0]).toMatchObject({ id: result.event.id, task_type: 'clean_leaves', by_name: 'Test member' });
  const restored = await request('/api/care-events/' + result.event.id + '/undo', 'POST');
  expect(restored.status).toBe(200);
  expect((await restored.json()).task).toMatchObject({ dueOn: '2026-05-31' });
});

test('sets and clears per-task intervals, without affecting other tasks', async () => {
  const before = await tasks();
  expect((await request(plantSettings())).status).toBe(200);
  const saved = await request(plantSettings(), 'PUT', { customIntervals: { clean_leaves: 5, repot: 365 } });
  expect(saved.status).toBe(200);
  expect(await saved.json()).toEqual({ customIntervals: { clean_leaves: 5, repot: 365 } });
  expect(await (await request(plantSettings())).json()).toEqual({ customIntervals: { clean_leaves: 5, repot: 365 } });
  expect(await tasks()).toMatchObject({ clean_leaves: { intervalDays: 5, dueOn: '2026-05-06', basedOn: expect.arrayContaining([expect.objectContaining({ source: 'override', value: 5 })]) }, repot: { intervalDays: 365, dueOn: '2027-05-01' }, water: before.water });
  expect((await request(plantSettings(), 'PUT', { customIntervals: {} })).status).toBe(200);
  expect(await tasks()).toMatchObject({ clean_leaves: before.clean_leaves, repot: before.repot });
});

test('removed care endpoints and task types return 404', async () => {
  expect((await request('/api/household/settings')).status).toBe(404);
  for (const action of ['complete', 'snooze', 'skip']) expect((await request('/api/plants/' + plantId + '/tasks/mist/' + action, 'POST', { request_id: randomUUID() })).status).toBe(404);
  expect((await request('/api/tasks/mist/complete-all', 'POST', { request_id: randomUUID() })).status).toBe(404);
});

test('validates intervals without partially changing settings', async () => {
  const before = await (await request(plantSettings())).json();
  for (const days of [0, 366, 1.5, '5', null]) {
    expect((await request(plantSettings(), 'PUT', { customIntervals: { water: 10, clean_leaves: days } })).status).toBe(422);
  }
  for (const customIntervals of [undefined, null, [], { feed: 5 }]) {
    expect((await request(plantSettings(), 'PUT', { customIntervals })).status).toBe(422);
  }
  for (const body of [null, [], 'bad']) expect((await request(plantSettings(), 'PUT', body)).status).toBe(400);
  const { cookie } = await harness.login();
  expect((await fetch(harness.base + plantSettings(), { method: 'PUT', headers: { cookie, 'content-type': 'application/json' }, body: '{' })).status).toBe(400);
  expect((await fetch(harness.base + plantSettings(), { method: 'PUT', headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: JSON.stringify({ customIntervals: {} }) })).status).toBe(403);
  expect(await (await request(plantSettings())).json()).toEqual(before);
  for (const days of [1, 365]) expect((await request(plantSettings(), 'PUT', { customIntervals: { water: days } })).status).toBe(200);
  await request(plantSettings(), 'PUT', before);
});

test('settings reads and writes require a session', async () => {
  for (const session of ['', 'fytorium_session=invalid']) {
    expect((await request(plantSettings(), 'GET', undefined, session)).status).toBe(401);
    expect((await request(plantSettings(), 'PUT', { customIntervals: {} }, session)).status).toBe(401);
  }
});

test('plant settings cannot cross households', async () => {
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  // Membership has no invitation API until #16; plants and settings still use HTTP.
  await fixtureDb.query('DROP INDEX households_singleton');
  const household = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test other care household') RETURNING id");
  await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-settings-other', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  const other = await harness.login('synthetic-settings-other');
  const site = await (await other.request('/api/sites', 'POST', { name: 'Test foreign room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await other.request('/api/species/search?q=Testoria%20lucida')).json();
  const foreign = await (await other.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json();
  for (const [caller, id] of [[request, foreign.id], [other.request, plantId]] as const) {
    for (const method of ['GET', 'PUT']) expect((await caller('/api/plants/' + id + '/care-settings', method, method === 'PUT' ? { customIntervals: { water: 5 } } : undefined)).status).toBe(404);
  }
});

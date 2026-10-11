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
const householdSettings = '/api/household/settings';

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
  const saved = await request(plantSettings(), 'PUT', { misting_enabled: true, customIntervals: { clean_leaves: 5, repot: 365 } });
  expect(saved.status).toBe(200);
  expect(await saved.json()).toEqual({ misting_enabled: true, customIntervals: { clean_leaves: 5, repot: 365 } });
  expect(await (await request(plantSettings())).json()).toEqual({ misting_enabled: true, customIntervals: { clean_leaves: 5, repot: 365 } });
  expect(await tasks()).toMatchObject({ clean_leaves: { intervalDays: 5, dueOn: '2026-05-06', basedOn: expect.arrayContaining([expect.objectContaining({ source: 'override', value: 5 })]) }, repot: { intervalDays: 365, dueOn: '2027-05-01' }, water: before.water });
  expect((await request(plantSettings(), 'PUT', { misting_enabled: true, customIntervals: {} })).status).toBe(200);
  expect(await tasks()).toMatchObject({ clean_leaves: before.clean_leaves, repot: before.repot });
});

test('misting requires both household and plant switches, and disappears from Today', async () => {
  await request('/__test/clock', 'POST', { action: 'set', at: '2026-05-05T12:00:00Z' });
  expect(await tasks()).toMatchObject({ mist: { status: 'due', dueOn: '2026-05-04' } });
  expect((await (await request('/api/tasks?view=today')).json()).groups.mist).toHaveLength(1);
  expect((await request(householdSettings, 'PUT', { misting_enabled: false })).status).toBe(200);
  expect(await (await request(householdSettings)).json()).toEqual({ misting_enabled: false });
  expect(await tasks()).toMatchObject({ mist: { status: 'not_applicable' } });
  expect((await (await request('/api/tasks?view=today')).json()).groups.mist).toEqual([]);
  await request(plantSettings(), 'PUT', { misting_enabled: false, customIntervals: {} });
  await request(householdSettings, 'PUT', { misting_enabled: true });
  expect(await tasks()).toMatchObject({ mist: { status: 'not_applicable' } });
  await request(plantSettings(), 'PUT', { misting_enabled: true, customIntervals: {} });
  expect(await tasks()).toMatchObject({ mist: { status: 'due' } });
});

test('validates intervals and switches without partially changing settings', async () => {
  const before = await (await request(plantSettings())).json();
  for (const days of [0, 366, 1.5, '5', null]) {
    expect((await request(plantSettings(), 'PUT', { misting_enabled: false, customIntervals: { water: 10, mist: days } })).status).toBe(422);
  }
  for (const customIntervals of [undefined, null, [], { feed: 5 }]) {
    expect((await request(plantSettings(), 'PUT', { misting_enabled: true, customIntervals })).status).toBe(422);
  }
  for (const path of [plantSettings(), householdSettings]) {
    for (const misting_enabled of [undefined, null, 'false', 0]) expect((await request(path, 'PUT', { misting_enabled, customIntervals: {} })).status).toBe(422);
    for (const body of [null, [], 'bad']) expect((await request(path, 'PUT', body)).status).toBe(400);
    const { cookie } = await harness.login();
    expect((await fetch(harness.base + path, { method: 'PUT', headers: { cookie, 'content-type': 'application/json' }, body: '{' })).status).toBe(400);
    expect((await fetch(harness.base + path, { method: 'PUT', headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: JSON.stringify({ misting_enabled: false, customIntervals: {} }) })).status).toBe(403);
  }
  expect(await (await request(plantSettings())).json()).toEqual(before);
  expect(await (await request(householdSettings)).json()).toEqual({ misting_enabled: true });
  for (const days of [1, 365]) expect((await request(plantSettings(), 'PUT', { misting_enabled: true, customIntervals: { water: days } })).status).toBe(200);
  await request(plantSettings(), 'PUT', before);
});

test('settings reads and writes require a session', async () => {
  for (const path of [plantSettings(), householdSettings]) {
    for (const session of ['', 'fytorium_session=invalid']) {
      expect((await request(path, 'GET', undefined, session)).status).toBe(401);
      expect((await request(path, 'PUT', { misting_enabled: false, customIntervals: {} }, session)).status).toBe(401);
    }
  }
});

test('plant settings and household switches cannot cross households', async () => {
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
    for (const method of ['GET', 'PUT']) expect((await caller('/api/plants/' + id + '/care-settings', method, method === 'PUT' ? { misting_enabled: false, customIntervals: { water: 5 } } : undefined)).status).toBe(404);
  }
  await other.request(householdSettings, 'PUT', { misting_enabled: false, household_id: household.rows[0].id });
  expect(await (await request(householdSettings)).json()).toEqual({ misting_enabled: true });
  await request(householdSettings, 'PUT', { misting_enabled: true, household_id: household.rows[0].id });
  expect(await (await other.request(householdSettings)).json()).toEqual({ misting_enabled: false });
  expect((await (await other.request('/api/plants/' + foreign.id + '/tasks')).json()).mist.status).toBe('not_applicable');
  expect((await tasks()).mist.status).toBe('due');
});

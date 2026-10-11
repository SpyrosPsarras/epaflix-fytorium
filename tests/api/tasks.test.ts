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
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2026-10-24T22:30:00Z' }) });
  ({ request } = await harness.login());
  const site = await (await request('/api/sites', 'POST', {
    name: 'Test watering room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false,
  })).json();
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const added = await request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname: 'Test thirsty plant' });
  expect(added.status).toBe(201);
  plantId = (await added.json()).id;
});
afterAll(async () => { if (fixtureDb) await fixtureDb.end(); });

const tasks = () => request('/api/plants/' + plantId + '/tasks').then(response => response.json());
const history = () => request('/api/plants/' + plantId + '/history').then(response => response.json());
const complete = (requestId = randomUUID()) => request('/api/plants/' + plantId + '/tasks/water/complete', 'POST', { request_id: requestId });
const undo = (eventId: string) => request('/api/care-events/' + eventId + '/undo', 'POST');


test('starts due on the household added date, completes with a member and recomputes from that moment', async () => {
  expect(await tasks()).toMatchObject({ water: { status: 'due', dueOn: '2026-10-25', lateDays: 0, intervalDays: 8 } });
  const done = await complete();
  expect(done.status).toBe(200);
  const result = await done.json();
  expect(result.task).toMatchObject({ dueOn: '2026-11-02', intervalDays: 8, lateDays: 0 });
  expect(await tasks()).toMatchObject({ water: result.task });
  expect(await history()).toEqual([expect.objectContaining({ id: result.event.id, kind: 'completed', task_type: 'water', occurred_at: '2026-10-24T22:30:00.000Z', occurred_on: '2026-10-25', by_name: 'Test member', undone_at: null })]);
  expect(await (await request('/api/tasks?view=today')).json()).toMatchObject({ today: '2026-10-25', groups: { water: [] } });
});

test('clock advancement makes it late, and undo restores both the previous completion and added-date state', async () => {
  const first = (await history())[0];
  await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 864000000 });
  ({ request } = await harness.login());
  expect(await tasks()).toMatchObject({ water: { dueOn: '2026-11-02', lateDays: 1 } });
  const list = await (await request('/api/tasks?view=today')).json();
  expect(list).toMatchObject({ today: '2026-11-03', groups: { water: [expect.objectContaining({ plant_id: plantId, plant_name: 'Test thirsty plant', site_name: 'Test watering room', lateDays: 1 })] } });
  const second = await (await complete()).json();
  expect(second.task).toMatchObject({ dueOn: '2026-11-12', lateDays: 0 });
  const restored = await undo(second.event.id);
  expect(restored.status).toBe(200);
  expect((await restored.json()).task).toMatchObject({ dueOn: '2026-11-02', lateDays: 1 });
  expect(await history()).toEqual([expect.objectContaining({ id: second.event.id, undone_at: '2026-11-03T22:30:00.000Z', undone_by: expect.any(String), by_name: 'Test member' }), first]);
  expect((await undo(second.event.id)).status).toBe(409);
  const original = await undo(first.id);
  expect(original.status).toBe(200);
  expect((await original.json()).task).toMatchObject({ dueOn: '2026-10-25', lateDays: 9 });
});

test('concurrent duplicate completion taps create one care event, including a replay after undo', async () => {
  const key = randomUUID();
  const before = await history();
  const responses = await Promise.all([complete(key), complete(key), complete(key)]);
  for (const response of responses) expect(response.status).toBe(200);
  const results = await Promise.all(responses.map(response => response.json()));
  expect(new Set(results.map(result => result.event.id)).size).toBe(1);
  expect((await history()).length).toBe(before.length + 1);
  const eventId = results[0].event.id;
  const undos = await Promise.all([undo(eventId), undo(eventId)]);
  expect(undos.map(response => response.status).sort()).toEqual([200, 409]);
  const replay = await (await complete(key)).json();
  expect(replay.event).toMatchObject({ id: eventId, undone_at: expect.any(String) });
  expect((await history()).length).toBe(before.length + 1);
});

test('invalid JSON, missing keys, unknown tasks and cross-origin writes are refused without care changes', async () => {
  const before = await history();
  for (const body of [null, [], 'invalid']) expect((await request('/api/plants/' + plantId + '/tasks/water/complete', 'POST', body)).status).toBe(400);
  for (const request_id of [undefined, 42, 'bad-key']) expect((await request('/api/plants/' + plantId + '/tasks/water/complete', 'POST', { request_id })).status).toBe(422);
  expect((await request('/api/plants/' + plantId + '/tasks/feed/complete', 'POST', { request_id: randomUUID() })).status).toBe(404);
  for (const id of ['not-an-id', randomUUID()]) {
    expect((await request('/api/plants/' + id + '/tasks')).status).toBe(404);
    expect((await request('/api/plants/' + id + '/history')).status).toBe(404);
    expect((await request('/api/plants/' + id + '/tasks/water/complete', 'POST', { request_id: randomUUID() })).status).toBe(404);
    expect((await undo(id)).status).toBe(404);
  }
  const login = await harness.login();
  request = login.request;
  const { cookie } = login;
  for (const path of ['/api/plants/' + plantId + '/tasks/water/complete', '/api/care-events/' + before[0].id + '/undo']) {
    expect((await fetch(harness.base + path, { method: 'POST', headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: JSON.stringify({ request_id: randomUUID() }) })).status).toBe(403);
  }
  const malformed = await fetch(harness.base + '/api/plants/' + plantId + '/tasks/water/complete', { method: 'POST', headers: { cookie, 'content-type': 'application/json' }, body: '{' });
  expect(malformed.status).toBe(400);
  expect(await history()).toEqual(before);
});

test('all task reads and commands require a household session', async () => {
  const event = (await history())[0];
  for (const [path, method, body] of [
    ['/api/tasks?view=today', 'GET', undefined], ['/api/plants/' + plantId + '/tasks', 'GET', undefined],
    ['/api/plants/' + plantId + '/history', 'GET', undefined],
    ['/api/plants/' + plantId + '/tasks/water/complete', 'POST', { request_id: randomUUID() }],
    ['/api/care-events/' + event.id + '/undo', 'POST', undefined],
  ] as const) {
    for (const session of ['', 'fytorium_session=invalid']) expect((await request(path, method, body, session)).status).toBe(401);
  }
});

test('an email-only login is shown as the acting member in care history', async () => {
  ({ request } = await harness.login('synthetic-owner', { email: 'member@example.test' }));
  const result = await (await complete()).json();
  expect(await history()).toContainEqual(expect.objectContaining({ id: result.event.id, by_name: 'member@example.test' }));
  await undo(result.event.id);
  ({ request } = await harness.login());
});

test('tasks, event history, completion and undo cannot cross households, even with a known request key', async () => {
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  // Invitations have no API yet. SQL supplies membership; all care and plants use HTTP.
  await fixtureDb.query('DROP INDEX households_singleton');
  const household = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test care household') RETURNING id");
  await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-care-other', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  const other = await harness.login('synthetic-care-other');
  const foreignSite = await (await other.request('/api/sites', 'POST', { name: 'Test foreign care room', location: 'indoor', light: 'part_sun', humidity: 'normal', draft: false, grow_light: false })).json();
  const search = await (await other.request('/api/species/search?q=Testoria%20lucida')).json();
  const foreignPlant = await (await other.request('/api/plants', 'POST', { ...fixturePlant, site_id: foreignSite.id, search_id: search.search_id, gbif_key: 910001 })).json();
  const foreignKey = randomUUID();
  const foreignDone = await (await other.request('/api/plants/' + foreignPlant.id + '/tasks/water/complete', 'POST', { request_id: foreignKey })).json();
  for (const [path, method, body] of [
    ['/api/plants/' + foreignPlant.id + '/tasks', 'GET', undefined], ['/api/plants/' + foreignPlant.id + '/history', 'GET', undefined],
    ['/api/plants/' + foreignPlant.id + '/tasks/water/complete', 'POST', { request_id: randomUUID() }],
    ['/api/care-events/' + foreignDone.event.id + '/undo', 'POST', undefined],
  ] as const) expect((await request(path, method, body)).status).toBe(404);
  const before = await history();
  expect((await complete(foreignKey)).status).toBe(409);
  expect(await history()).toEqual(before);
  expect((await (await other.request('/api/plants/' + foreignPlant.id + '/history')).json())[0]).toMatchObject({ undone_at: null });
  await other.request('/api/care-events/' + foreignDone.event.id + '/undo', 'POST');
  expect((await (await request('/api/tasks?view=today')).json()).groups.water.map((task: { plant_id: string }) => task.plant_id)).not.toContain(foreignPlant.id);
  expect((await (await other.request('/api/tasks?view=today')).json()).groups.water.map((task: { plant_id: string }) => task.plant_id)).toEqual([foreignPlant.id]);
});

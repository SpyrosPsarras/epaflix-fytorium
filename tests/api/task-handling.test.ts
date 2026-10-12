import { afterAll, beforeAll, expect, test } from 'vitest';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let plantId: string;
let siteId: string;
let fixtureDb: pg.Pool | undefined;
const clock = (at: string) => request('/__test/clock', 'POST', { action: 'set', at });
const tasks = () => request(`/api/plants/${plantId}/tasks`).then(r => r.json());
const history = () => request(`/api/plants/${plantId}/history`).then(r => r.json());
const act = (action: string, type = 'water', request_id = randomUUID()) => request(`/api/plants/${plantId}/tasks/${type}/${action}`, 'POST', { request_id });
const undo = (id: string) => request(`/api/care-events/${id}/undo`, 'POST');
const undoBatch = (id: string) => request(`/api/task-batches/${id}/undo`, 'POST');
async function addPlant(site_id: string, nickname: string) {
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const response = await request('/api/plants', 'POST', { ...fixturePlant, site_id, nickname, search_id: search.search_id, gbif_key: 910001 });
  expect(response.status).toBe(201);
  return (await response.json()).id as string;
}

beforeAll(async () => {
  ({ request } = await harness.login());
  await clock('2026-05-01T12:00:00Z');
  ({ request } = await harness.login());
  const site = await (await request('/api/sites', 'POST', { name: 'Test handling room', location: 'indoor', light: 'part_sun', humidity: 'normal', draft: false, grow_light: false })).json();
  siteId = site.id;
  plantId = await addPlant(siteId, 'Test handling plant');
});
afterAll(async () => { await fixtureDb?.end(); });

test('snooze and skip record history, are idempotent and undo restores the exact previous schedule', async () => {
  await clock('2026-05-03T12:00:00Z');
  const before = await tasks();
  const key = randomUUID();
  const snoozed = await act('snooze', 'water', key);
  expect(snoozed.status).toBe(200);
  const result = await snoozed.json();
  expect(result).toMatchObject({ event: { kind: 'snoozed', occurred_on: '2026-05-03' }, task: { dueOn: '2026-05-05', intervalDays: 7 } });
  expect((await (await act('snooze', 'water', key)).json()).event.id).toBe(result.event.id);
  const skipped = await act('skip');
  expect(skipped.status).toBe(200);
  const skip = await skipped.json();
  expect(skip).toMatchObject({ event: { kind: 'skipped' }, task: { dueOn: '2026-05-10', intervalDays: 7 } });
  expect((await (await undo(skip.event.id)).json()).task.dueOn).toBe('2026-05-05');
  expect((await (await undo(result.event.id)).json()).task).toEqual(before.water);
  expect(await history()).toEqual(expect.arrayContaining([expect.objectContaining({ kind: 'snoozed', undone_at: expect.any(String) }), expect.objectContaining({ kind: 'skipped', undone_at: expect.any(String) })]));
  // Equal-clock commands are ordered by the server, not by random event UUIDs.
  const second = await (await act('snooze')).json();
  const done = await (await act('complete')).json();
  expect(done.task.dueOn).toBe('2026-05-10');
  expect((await (await undo(done.event.id)).json()).task.dueOn).toBe('2026-05-05');
  await undo(second.event.id);
});

const completeAll = (request_id = randomUUID()) => request(`/api/plants/${plantId}/tasks/complete-all`, 'POST', { request_id });

test('complete-all is one atomic, idempotent action for exactly due or late tasks, and undo reverses the batch', async () => {
  await clock('2026-06-05T12:00:00Z');
  ({ request } = await harness.login());
  const before = await tasks();
  const key = randomUUID();
  const results = await Promise.all([completeAll(key), completeAll(key)]);
  for (const response of results) expect(response.status).toBe(200);
  const [one, two] = await Promise.all(results.map(r => r.json()));
  expect(one.events.map((event: { task_type: string }) => event.task_type)).toEqual(['water', 'fertilize', 'mist', 'clean_leaves', 'remove_dead']);
  expect(two.events).toEqual(one.events);
  expect(new Set(one.events.map((event: { batch_id: string }) => event.batch_id))).toEqual(new Set([key]));
  expect(await tasks()).toMatchObject({ water: { dueOn: '2026-06-12' }, mist: { dueOn: '2026-06-08' }, repot: before.repot, cut_back: before.cut_back });
  expect((await undo(one.events[0].id)).status).toBe(409);
  const restored = await undoBatch(key);
  expect(restored.status).toBe(200);
  expect(await tasks()).toEqual(before);
  expect((await undo(one.events[1].id)).status).toBe(409);
  expect((await (await completeAll(key)).json()).events.every((event: { undone_at: string | null }) => event.undone_at !== null)).toBe(true);
  expect(await tasks()).toEqual(before);
});

test('a batch failure rolls back all earlier completions and an empty batch remains idempotent', async () => {
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  // Inject a write failure, then observe rollback only through HTTP history and schedules.
  await fixtureDb.query(`CREATE FUNCTION fail_test_batch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.batch_id IS NOT NULL AND NEW.task_type = 'mist' THEN RAISE EXCEPTION 'Synthetic batch failure'; END IF; RETURN NEW; END $$;
    CREATE TRIGGER fail_test_batch BEFORE INSERT ON care_events FOR EACH ROW EXECUTE FUNCTION fail_test_batch()`);
  const beforeHistory = await history();
  const beforeTasks = await tasks();
  const key = randomUUID();
  try {
    expect((await completeAll(key)).status).toBe(500);
    expect(await history()).toEqual(beforeHistory);
    expect(await tasks()).toEqual(beforeTasks);
  } finally { await fixtureDb.query('DROP TRIGGER fail_test_batch ON care_events; DROP FUNCTION fail_test_batch()'); }
  const success = await (await completeAll(key)).json();
  const emptyKey = randomUUID();
  expect((await (await completeAll(emptyKey)).json()).events).toEqual([]);
  await undoBatch(success.events[0].batch_id);
  expect((await (await completeAll(emptyKey)).json()).events).toEqual([]);
  expect(await tasks()).toEqual(beforeTasks);
});

const repot = (pot: Record<string, unknown>, request_id = randomUUID()) => request(`/api/plants/${plantId}/repot`, 'POST', { ...pot, request_id });
const newPot = { pot_type: 'terracotta', pot_size_cm: 24, soil_type: 'aroid' };

test('repot validates the pot, records completion, resets its schedule, and undo restores previous pot data', async () => {
  const originalPlant = await (await request(`/api/plants/${plantId}`)).json();
  const originalTasks = await tasks();
  for (const bad of [{ pot_type: 'bag' }, { soil_type: 'peat' }, { pot_size_cm: 0 }, { pot_size_cm: 1.5 }, { pot_size_cm: '24' }, { pot_size_cm: 2147483648 }]) {
    expect((await repot({ ...newPot, ...bad })).status).toBe(422);
  }
  const key = randomUUID();
  const response = await repot(newPot, key);
  expect(response.status).toBe(200);
  const result = await response.json();
  expect(result).toMatchObject({ event: { task_type: 'repot', kind: 'completed', previous_pot: { pot_type: 'plastic', pot_size_cm: null, soil_type: 'all_purpose' } }, task: { dueOn: '2029-06-04', intervalDays: 1095 } });
  expect(await (await request(`/api/plants/${plantId}`)).json()).toMatchObject(newPot);
  expect((await (await repot(newPot, key)).json()).event.id).toBe(result.event.id);
  expect((await repot({ ...newPot, soil_type: 'cactus' }, key)).status).toBe(409);
  expect((await undo(result.event.id)).status).toBe(200);
  expect(await (await request(`/api/plants/${plantId}`)).json()).toEqual(originalPlant);
  expect(await tasks()).toEqual(originalTasks);
  expect((await undo(result.event.id)).status).toBe(409);
  // Replaying an undone repot must not reapply its pot edits.
  expect((await (await repot(newPot, key)).json()).event.undone_at).not.toBeNull();
  expect(await (await request(`/api/plants/${plantId}`)).json()).toEqual(originalPlant);
});

test('Upcoming shares Today\'s household calendar day, groups 14 days, omits inapplicable tasks and uses persisted member settings', async () => {
  await clock('2026-06-05T22:30:00Z'); // June 6 in Europe/Oslo.
  const outside = await (await request('/api/sites', 'POST', { name: 'Test outdoor handling site', location: 'outdoor', light: 'part_sun', humidity: 'normal', draft: false, grow_light: false })).json();
  const outdoorId = await addPlant(outside.id, 'A test outdoor plant');
  const settings = { sort: 'plant', filter: 'outdoor', show_completed: true };
  const saved = await request('/api/tasks/settings', 'PUT', settings);
  expect(saved.status).toBe(200);
  expect(await saved.json()).toEqual(settings);
  ({ request } = await harness.login());
  expect(await (await request('/api/tasks/settings')).json()).toEqual(settings);
  const today = await (await request('/api/tasks?view=today')).json();
  const upcomingResponse = await request('/api/tasks?view=upcoming');
  expect(upcomingResponse.status).toBe(200);
  const upcoming = await upcomingResponse.json();
  expect(upcoming.today).toBe('2026-06-06');
  expect(upcoming.sections[0]).toMatchObject({ key: '2026-06-06', label: '2026-06-06' });
  expect(upcoming.sections.map((section: { key: string }) => section.key)).toEqual(['2026-06-06', '2026-06-18']);
  expect(upcoming.sections[0].tasks).toEqual(today.groups.water);
  expect(upcoming.groups.mist).toEqual([]);
  expect(upcoming.groups.clean_leaves).toEqual([]);
  expect(upcoming.groups.water.map((row: { plant_id: string }) => row.plant_id)).toEqual([outdoorId]);
  const done = await (await request(`/api/plants/${outdoorId}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).json();
  const completed = await (await request('/api/tasks?view=today')).json();
  expect(completed.groups.water).toEqual([expect.objectContaining({ plant_id: outdoorId, completed_event: expect.objectContaining({ id: done.event.id }) })]);
  await request('/api/tasks/settings', 'PUT', { ...settings, show_completed: false });
  expect((await (await request('/api/tasks?view=today')).json()).groups.water).toEqual([]);
  await undo(done.event.id);
  await request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: false });
  const bulk = await (await request(`/api/plants/${outdoorId}/tasks/complete-all`, 'POST', { request_id: randomUUID() })).json();
  expect(bulk.events.map((event: { task_type: string }) => event.task_type)).toEqual(['water']);
  await undoBatch(bulk.events[0].batch_id);
});

test('sorting and each location filter apply on the server and all settings validate before changing persisted values', async () => {
  const baseline = { sort: 'task', filter: 'all', show_completed: false };
  for (const sort of ['plant', 'site', 'task']) {
    const desired = { ...baseline, sort };
    expect((await request('/api/tasks/settings', 'PUT', desired)).status).toBe(200);
    const list = await (await request('/api/tasks?view=today')).json();
    expect(list.settings).toEqual(desired);
    const labels = list.sections.map((section: { label: string }) => section.label);
    expect(labels).toEqual(sort === 'task' ? ['Water', 'Fertilize', 'Mist', 'Clean leaves', 'Remove dead flowers and leaves', 'Seasonal cut-back', 'Repot'] : sort === 'plant' ? ['A test outdoor plant', 'Test handling plant'] : ['Test handling room', 'Test outdoor handling site']);
  }
  await request('/api/tasks/settings', 'PUT', { ...baseline, filter: 'indoor' });
  const indoors = await (await request('/api/tasks?view=today')).json();
  expect(indoors.groups.water.map((row: { plant_id: string }) => row.plant_id)).toEqual([plantId]);
  for (const bad of [{ sort: 'date' }, { sort: ['task'] }, { filter: 'garden' }, { filter: null }, { show_completed: 'true' }, { show_completed: 1 }]) {
    expect((await request('/api/tasks/settings', 'PUT', { ...baseline, ...bad })).status).toBe(422);
  }
  expect(await (await request('/api/tasks/settings')).json()).toEqual({ ...baseline, filter: 'indoor' });
  expect((await request('/api/tasks?view=unbounded')).status).toBe(422);
  await request('/api/tasks/settings', 'PUT', baseline);
});

test('all new commands validate JSON and request keys, refuse inapplicable care and cross-origin writes without changing history', async () => {
  const before = await history();
  const commands = [`/api/plants/${plantId}/tasks/water/snooze`, `/api/plants/${plantId}/tasks/water/skip`, `/api/plants/${plantId}/tasks/complete-all`, `/api/plants/${plantId}/repot`];
  const { cookie } = await harness.login();
  for (const path of [...commands, '/api/tasks/settings']) {
    const method = path.endsWith('/settings') ? 'PUT' : 'POST';
    for (const body of [null, [], 'bad']) expect((await request(path, method, body)).status).toBe(400);
    expect((await fetch(harness.base + path, { method, headers: { cookie, 'content-type': 'application/json' }, body: '{' })).status).toBe(400);
    expect((await fetch(harness.base + path, { method, headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: '{}' })).status).toBe(403);
  }
  for (const path of commands) {
    for (const request_id of [undefined, 3, 'bad']) expect((await request(path, 'POST', { ...newPot, request_id })).status).toBe(422);
  }
  await request(`/api/plants/${plantId}/care-settings`, 'PUT', { misting_enabled: false, customIntervals: {} });
  for (const action of ['snooze', 'skip']) expect((await act(action, 'mist')).status).toBe(422);
  await request(`/api/plants/${plantId}/care-settings`, 'PUT', { misting_enabled: true, customIntervals: {} });
  const key = randomUUID();
  const snoozed = await (await act('snooze', 'water', key)).json();
  expect((await act('skip', 'water', key)).status).toBe(409);
  expect((await completeAll(key)).status).toBe(409);
  expect((await repot(newPot, key)).status).toBe(409);
  await undo(snoozed.event.id);
  expect((await history()).length).toBe(before.length + 1);
});

test('snooze refuses future tasks without changing history, and due postponements undo exactly', async () => {
  const before = await tasks();
  const done = await (await act('complete')).json();
  expect(done.task.dueOn).toBe('2026-06-13');
  const completedHistory = await history();
  const refused = await act('snooze');
  expect(refused.status).toBe(409);
  expect((await refused.json()).message).toBe('Only due or late tasks can be snoozed.');
  expect(await history()).toEqual(completedHistory);
  expect((await tasks()).water).toEqual(done.task);
  await undo(done.event.id);
  const snoozed = await (await act('snooze')).json();
  expect(snoozed.task.dueOn).toBe('2026-06-08');
  const skipped = await (await act('skip')).json();
  expect(skipped.task.dueOn).toBe('2026-06-13');
  expect((await (await undo(skipped.event.id)).json()).task.dueOn).toBe('2026-06-08');
  expect((await (await undo(snoozed.event.id)).json()).task).toEqual(before.water);
  expect(await tasks()).toEqual(before);
});

test('repot undo refuses later edits to every pot field and keeps both the edit and event intact', async () => {
  for (const edit of [{ pot_type: 'metal' }, { pot_size_cm: 31 }, { soil_type: 'cactus' }]) {
    const name = 'Synthetic edited repot ' + randomUUID();
    const id = await addPlant(siteId, name);
    const path = `/api/plants/${id}`;
    try {
      const repotted = await (await request(path + '/repot', 'POST', { ...newPot, request_id: randomUUID() })).json();
      const plant = await (await request(path)).json();
      expect((await request(path, 'PUT', { ...plant, ...edit })).status).toBe(200);
      const edited = await (await request(path)).json();
      const beforeHistory = await (await request(path + '/history')).json();
      const refused = await undo(repotted.event.id);
      expect(refused.status).toBe(409);
      expect((await refused.json()).message).toBe('Pot fields changed after this repot. Undo would overwrite those edits.');
      expect(await (await request(path)).json()).toEqual(edited);
      expect(await (await request(path + '/history')).json()).toEqual(beforeHistory);
    } finally { expect((await request(path, 'DELETE', { confirm_name: name })).status).toBe(204); }
  }
});

test('multiple repots undo newest first without losing later pot data or undoing a refused event', async () => {
  const original = await (await request(`/api/plants/${plantId}`)).json();
  const first = await (await repot(newPot)).json();
  const secondPot = { pot_type: 'ceramic', pot_size_cm: 18, soil_type: 'cactus' };
  const second = await (await repot(secondPot)).json();
  expect((await undo(first.event.id)).status).toBe(409);
  expect(await (await request(`/api/plants/${plantId}`)).json()).toMatchObject(secondPot);
  expect(await history()).toContainEqual(expect.objectContaining({ id: first.event.id, undone_at: null }));
  await undo(second.event.id);
  expect(await (await request(`/api/plants/${plantId}`)).json()).toMatchObject(newPot);
  await undo(first.event.id);
  expect(await (await request(`/api/plants/${plantId}`)).json()).toEqual(original);
});

test('today shows completed repotting even when the new pot makes repotting inapplicable, while Upcoming never includes inapplicable tasks', async () => {
  const result = await (await repot({ pot_type: 'in_ground', pot_size_cm: null, soil_type: 'other' })).json();
  expect(result.task.status).toBe('not_applicable');
  await request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: true });
  const today = await (await request('/api/tasks?view=today')).json();
  expect(today.groups.repot).toContainEqual(expect.objectContaining({ plant_id: plantId, completed_event: expect.objectContaining({ id: result.event.id }) }));
  const upcoming = await (await request('/api/tasks?view=upcoming')).json();
  expect(upcoming.groups.repot).toEqual([]);
  await undo(result.event.id);
  await request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: false });
});

test('snooze, skip, bulk and task views use catalogue overrides, including applicability and pruning months', async () => {
  await clock('2026-06-01T12:00:00Z');
  const id = await addPlant(siteId, 'Test catalogue handling plant');
  await clock('2026-06-05T22:30:00Z');
  const path = `/api/plants/${id}`;
  const overrides = { wateringIntervalDays: 21, fertilizingIntervalDays: 60, humidityNeed: 'low', pruningMonths: [6] };
  try {
    for (const [field, value] of Object.entries(overrides)) expect((await request('/api/species/910001/care/overrides/' + field, 'PUT', { value })).status).toBe(200);
    const before = await (await request(path + '/tasks')).json();
    expect(before.water).toMatchObject({ intervalDays: 21, dueOn: '2026-06-01' });
    expect(before.water.basedOn).toContainEqual(expect.objectContaining({ input: 'wateringIntervalDays', value: 21, source: 'override' }));
    expect(before.mist.status).toBe('not_applicable');
    const snoozed = await (await request(path + '/tasks/water/snooze', 'POST', { request_id: randomUUID() })).json();
    expect(snoozed.task).toMatchObject({ intervalDays: 21, dueOn: '2026-06-08' });
    await undo(snoozed.event.id);
    const skipped = await (await request(path + '/tasks/water/skip', 'POST', { request_id: randomUUID() })).json();
    expect(skipped.task).toMatchObject({ intervalDays: 21, dueOn: '2026-06-27' });
    await undo(skipped.event.id);
    for (const view of ['today', 'upcoming']) {
      const list = await (await request('/api/tasks?view=' + view)).json();
      expect(list.groups.water).toContainEqual(expect.objectContaining({ plant_id: id, intervalDays: 21 }));
      expect(list.groups.mist.map((row: { plant_id: string }) => row.plant_id)).not.toContain(id);
    }
    const handled = await (await request(path + '/tasks/complete-all', 'POST', { request_id: randomUUID() })).json();
    expect(handled.events.map((event: { task_type: string }) => event.task_type)).toEqual(['water', 'cut_back']);
    expect(handled.tasks.water.dueOn).toBe('2026-06-27');
    expect(handled.tasks.cut_back.dueOn).toBe('2027-06-01');
    await undoBatch(handled.events[0].batch_id);
    expect(await (await request(path + '/tasks')).json()).toEqual(before);
  } finally {
    for (const field of Object.keys(overrides)) await request('/api/species/910001/care/overrides/' + field, 'DELETE');
    await request(path, 'DELETE', { confirm_name: 'Test catalogue handling plant' });
  }
});

test('graveyard refuses every care action and replay without changing history or pot fields, and hides all task views', async () => {
  const id = await addPlant(siteId, 'Test buried handling plant');
  const path = `/api/plants/${id}`;
  const key = randomUUID();
  const completed = await (await request(path + '/tasks/water/complete', 'POST', { request_id: key })).json();
  await request(path + '/graveyard', 'POST');
  const originalPlant = await (await request(path)).json();
  const originalHistory = await (await request(path + '/history')).json();
  for (const [suffix, body] of [
    ['/tasks/water/complete', { request_id: key }],
    ['/tasks/water/snooze', { request_id: randomUUID() }],
    ['/tasks/water/skip', { request_id: randomUUID() }],
    ['/tasks/complete-all', { request_id: randomUUID() }],
    ['/repot', { ...newPot, request_id: randomUUID() }],
  ] as const) expect((await request(path + suffix, 'POST', body)).status).toBe(409);
  expect((await undo(completed.event.id)).status).toBe(409);
  expect(await (await request(path)).json()).toEqual(originalPlant);
  expect(await (await request(path + '/history')).json()).toEqual(originalHistory);
  expect(await (await request(path + '/tasks')).json()).toEqual({});
  for (const sort of ['task', 'plant', 'site']) {
    for (const filter of ['all', 'indoor', 'outdoor']) {
      await request('/api/tasks/settings', 'PUT', { sort, filter, show_completed: true });
      for (const view of ['today', 'upcoming']) {
        const list = await (await request('/api/tasks?view=' + view)).json();
        for (const rows of Object.values(list.groups) as { plant_id: string }[][]) expect(rows.map(row => row.plant_id)).not.toContain(id);
      }
    }
  }
  await request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: false });
  await request(path + '/restore', 'POST', {});
  expect((await undo(completed.event.id)).status).toBe(200);
  await request(path, 'DELETE', { confirm_name: originalPlant.display_name });
});

test('new views, settings and care commands refuse unauthenticated and cross-household access, and settings belong to each member', async () => {
  const baseline = { sort: 'task', filter: 'all', show_completed: false };
  const paths = [
    ['/api/tasks?view=upcoming', 'GET', undefined], ['/api/tasks/settings', 'GET', undefined], ['/api/tasks/settings', 'PUT', baseline],
    [`/api/plants/${plantId}/tasks/water/snooze`, 'POST', { request_id: randomUUID() }],
    [`/api/plants/${plantId}/tasks/water/skip`, 'POST', { request_id: randomUUID() }],
    [`/api/plants/${plantId}/tasks/complete-all`, 'POST', { request_id: randomUUID() }],
    [`/api/plants/${plantId}/repot`, 'POST', { ...newPot, request_id: randomUUID() }],
  ] as const;
  for (const [path, method, body] of paths) for (const cookie of ['', 'fytorium_session=invalid']) expect((await request(path, method, body, cookie)).status).toBe(401);
  await fixtureDb!.query('DROP INDEX households_singleton');
  const household = await fixtureDb!.query("INSERT INTO households (name) VALUES ('Test other handling household') RETURNING id");
  await fixtureDb!.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-handling-other', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  const other = await harness.login('synthetic-handling-other');
  const originalHistory = await history();
  for (const [path, method, body] of paths.slice(3)) expect((await other.request(path, method, body)).status).toBe(404);
  for (const id of ['not-an-id', randomUUID()]) {
    for (const action of ['snooze', 'skip']) expect((await request(`/api/plants/${id}/tasks/water/${action}`, 'POST', { request_id: randomUUID() })).status).toBe(404);
    expect((await request(`/api/plants/${id}/tasks/complete-all`, 'POST', { request_id: randomUUID() })).status).toBe(404);
    expect((await request(`/api/plants/${id}/repot`, 'POST', { ...newPot, request_id: randomUUID() })).status).toBe(404);
  }
  expect((await other.request(`/api/care-events/${originalHistory[0].id}/undo`, 'POST')).status).toBe(404);
  expect((await (await other.request('/api/tasks?view=upcoming')).json()).sections).toEqual([]);
  await other.request('/api/tasks/settings', 'PUT', { sort: 'site', filter: 'outdoor', show_completed: true });
  expect(await (await request('/api/tasks/settings')).json()).toEqual(baseline);
  expect(await history()).toEqual(originalHistory);
  // Fixture membership is setup only; preferences are exercised through sessions and HTTP.
  await fixtureDb!.query("INSERT INTO members (household_id, issuer, subject, role) SELECT household_id, issuer, 'synthetic-handling-family', 'family' FROM members WHERE subject = 'synthetic-owner'");
  const family = await harness.login('synthetic-handling-family');
  await family.request('/api/tasks/settings', 'PUT', { sort: 'plant', filter: 'indoor', show_completed: true });
  const newSession = await harness.login('synthetic-handling-family');
  expect(await (await newSession.request('/api/tasks/settings')).json()).toEqual({ sort: 'plant', filter: 'indoor', show_completed: true });
  expect(await (await request('/api/tasks/settings')).json()).toEqual(baseline);
});

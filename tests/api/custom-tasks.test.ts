import { afterAll, beforeAll, expect, test } from 'vitest';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let siteId: string;
let plantId: string;
let plantTaskId: string;
let siteTaskId: string;
let fixtureDb: pg.Pool | undefined;
let laterPlantId: string;
afterAll(async () => { await fixtureDb?.end(); });
const clock = (at: string) => request('/__test/clock', 'POST', { action: 'set', at });
async function addPlant(site_id: string, nickname: string) {
  const search = await (await request('/api/species/search?q=Testoria%20lucida')).json();
  const response = await request('/api/plants', 'POST', { ...fixturePlant, site_id, nickname, search_id: search.search_id, gbif_key: 910001 });
  expect(response.status).toBe(201);
  return (await response.json()).id as string;
}
const tasks = (id = plantId) => request(`/api/plants/${id}/tasks`).then(r => r.json());
const act = (task: string, action: string, id = plantId, request_id = randomUUID()) => request(`/api/plants/${id}/tasks/${task}/${action}`, 'POST', { request_id });
const undo = (event: string) => request(`/api/care-events/${event}/undo`, 'POST');

beforeAll(async () => {
  ({ request } = await harness.login());
  await clock('2026-05-01T12:00:00Z');
  ({ request } = await harness.login());
  const site = await (await request('/api/sites', 'POST', { name: 'Test custom room', location: 'indoor', light: 'part_sun', humidity: 'normal', draft: false, grow_light: false })).json();
  siteId = site.id;
  plantId = await addPlant(siteId, 'Test custom plant');
});

test('create and edit plant and site tasks, scheduling from task creation with current names and intervals', async () => {
  await clock('2026-05-10T12:00:00Z');
  ({ request } = await harness.login());
  for (const target of [{ plant_id: plantId }, { site_id: siteId }]) {
    const response = await request('/api/custom-tasks', 'POST', { ...target, name: '  Test inspect  ', interval_days: 7 });
    expect(response.status).toBe(201);
    const task = await response.json();
    expect(task).toMatchObject({ ...target, name: 'Test inspect', interval_days: 7 });
    if ('plant_id' in target) plantTaskId = task.id;
    else siteTaskId = task.id;
    expect((await tasks()).customTasks[task.id]).toMatchObject({ name: 'Test inspect', dueOn: '2026-05-17', intervalDays: 7 });
    const edited = await request('/api/custom-tasks/' + task.id, 'PUT', { name: 'Test check roots', interval_days: 3 });
    expect(edited.status).toBe(200);
    expect((await tasks()).customTasks[task.id]).toMatchObject({ name: 'Test check roots', dueOn: '2026-05-13', intervalDays: 3 });
    const query = 'plant_id' in target ? 'plant_id=' + plantId : 'site_id=' + siteId;
    expect(await (await request('/api/custom-tasks?' + query)).json()).toContainEqual(expect.objectContaining({ id: task.id, name: 'Test check roots' }));
  }
});

test('plant and site tasks complete, snooze, skip, replay and undo through the same command path', async () => {
  await clock('2026-05-13T12:00:00Z');
  for (const id of [plantTaskId, siteTaskId]) {
    const key = randomUUID();
    const doneResponse = await act(id, 'complete', plantId, key);
    expect(doneResponse.status).toBe(200);
    const done = await doneResponse.json();
    expect(done).toMatchObject({ event: { task_type: null, custom_task_id: id, kind: 'completed', occurred_on: '2026-05-13' }, task: { intervalDays: 3, dueOn: '2026-05-16' } });
    expect((await (await act(id, 'complete', plantId, key)).json()).event.id).toBe(done.event.id);
    expect((await act(id, 'snooze', plantId, key)).status).toBe(409);
    const snoozed = await (await act(id, 'snooze')).json();
    expect(snoozed.task.dueOn).toBe('2026-05-15');
    const skipped = await (await act(id, 'skip')).json();
    expect(skipped.task.dueOn).toBe('2026-05-16');
    expect((await (await undo(skipped.event.id)).json()).task.dueOn).toBe('2026-05-15');
    expect((await (await undo(snoozed.event.id)).json()).task.dueOn).toBe('2026-05-16');
    expect((await (await undo(done.event.id)).json()).task.dueOn).toBe('2026-05-13');
    expect((await undo(done.event.id)).status).toBe(409);
    expect((await (await act(id, 'complete', plantId, key)).json()).event.undone_at).not.toBeNull();
    expect((await tasks()).customTasks[id].dueOn).toBe('2026-05-13');
  }
});

test('Handle all includes custom tasks in one idempotent batch, and batch undo undoes built-ins too', async () => {
  const before = await tasks();
  const key = randomUUID();
  const path = `/api/plants/${plantId}/tasks/complete-all`;
  const result = await (await request(path, 'POST', { request_id: key })).json();
  expect(result.events.filter((e: { custom_task_id: string | null }) => e.custom_task_id).map((e: { custom_task_id: string }) => e.custom_task_id).sort()).toEqual([plantTaskId, siteTaskId].sort());
  expect(result.events.some((e: { task_type: string | null }) => e.task_type === 'water')).toBe(true);
  expect(new Set(result.events.map((e: { batch_id: string }) => e.batch_id))).toEqual(new Set([key]));
  expect((await (await request(path, 'POST', { request_id: key })).json()).events).toEqual(result.events);
  expect(result.tasks.customTasks[plantTaskId].dueOn).toBe('2026-05-16');
  const customEvent = result.events.find((e: { custom_task_id: string | null }) => e.custom_task_id === siteTaskId);
  expect((await undo(customEvent.id)).status).toBe(409);
  expect((await request(`/api/task-batches/${customEvent.batch_id}/undo`, 'POST')).status).toBe(200);
  expect(await tasks()).toEqual(before);
  expect((await (await request(path, 'POST', { request_id: key })).json()).events.every((e: { undone_at: string | null }) => e.undone_at !== null)).toBe(true);
});

test('Today and Upcoming group custom names, honor every sort/location filter and show completed after reload', async () => {
  const done = await (await act(plantTaskId, 'complete')).json();
  const baseline = { sort: 'task', filter: 'all', show_completed: false };
  await request('/api/tasks/settings', 'PUT', baseline);
  expect((await (await request('/api/tasks?view=today')).json()).groups[plantTaskId]).toBeUndefined();
  for (const sort of ['task', 'plant', 'site']) {
    for (const filter of ['all', 'indoor', 'outdoor']) {
      await request('/api/tasks/settings', 'PUT', { sort, filter, show_completed: true });
      for (const view of ['today', 'upcoming']) {
        const list = await (await request('/api/tasks?view=' + view)).json();
        if (filter === 'outdoor') { expect(list.groups[plantTaskId]).toBeUndefined(); continue; }
        expect(list.groups[plantTaskId]).toContainEqual(expect.objectContaining({ plant_id: plantId, custom_task_name: 'Test check roots', completed_event: expect.objectContaining({ id: done.event.id }) }));
        expect(list.groups[siteTaskId]).toContainEqual(expect.objectContaining({ plant_id: plantId, custom_task_name: 'Test check roots' }));
        expect(list.sections).toContainEqual(expect.objectContaining({ label: view === 'upcoming' ? '2026-05-13' : sort === 'task' ? 'Test check roots' : sort === 'plant' ? 'Test custom plant' : 'Test custom room' }));
      }
    }
  }
  await undo(done.event.id);
  await request('/api/tasks/settings', 'PUT', baseline);
  const snoozed = await (await act(plantTaskId, 'snooze')).json();
  const upcoming = await (await request('/api/tasks?view=upcoming')).json();
  expect(upcoming.sections).toContainEqual(expect.objectContaining({ key: '2026-05-15', tasks: expect.arrayContaining([expect.objectContaining({ custom_task_id: plantTaskId })]) }));
  await undo(snoozed.event.id);
});

test('site tasks reach later additions independently, stop on moving off the site, and never reach the graveyard', async () => {
  await clock('2026-05-14T12:00:00Z');
  laterPlantId = await addPlant(siteId, 'Test later custom plant');
  expect((await tasks(laterPlantId)).customTasks[siteTaskId]).toMatchObject({ dueOn: '2026-05-17' });
  expect((await tasks(laterPlantId)).customTasks[plantTaskId]).toBeUndefined();
  await clock('2026-05-16T12:00:00Z');
  const done = await (await act(siteTaskId, 'complete')).json();
  expect(done.task.dueOn).toBe('2026-05-19');
  expect((await tasks(laterPlantId)).customTasks[siteTaskId].dueOn).toBe('2026-05-17');
  const otherSite = await (await request('/api/sites', 'POST', { name: 'Test other custom site', location: 'outdoor', light: 'part_sun', humidity: 'normal', draft: false, grow_light: false })).json();
  expect((await request(`/api/plants/${plantId}`, 'PUT', { ...fixturePlant, site_id: otherSite.id, nickname: 'Test custom plant' })).status).toBe(200);
  expect((await tasks()).customTasks[siteTaskId]).toBeUndefined();
  expect((await tasks()).customTasks[plantTaskId]).toBeDefined();
  expect((await act(siteTaskId, 'complete')).status).toBe(404);
  for (const view of ['today', 'upcoming']) expect((await (await request('/api/tasks?view=' + view)).json()).groups[siteTaskId]?.map((r: { plant_id: string }) => r.plant_id) ?? []).not.toContain(plantId);
  await request(`/api/plants/${plantId}`, 'PUT', { ...fixturePlant, site_id: siteId, nickname: 'Test custom plant' });
  const ownTask = await (await request('/api/custom-tasks', 'POST', { plant_id: laterPlantId, name: 'Test later plant task', interval_days: 1 })).json();
  const laterDone = await (await act(siteTaskId, 'complete', laterPlantId)).json();
  await request(`/api/plants/${laterPlantId}/graveyard`, 'POST');
  expect(await tasks(laterPlantId)).toEqual({});
  for (const action of ['complete', 'snooze', 'skip']) {
    expect((await act(siteTaskId, action, laterPlantId)).status).toBe(409);
    expect((await act(ownTask.id, action, laterPlantId)).status).toBe(409);
  }
  expect((await undo(laterDone.event.id)).status).toBe(409);
  for (const view of ['today', 'upcoming']) expect((await (await request('/api/tasks?view=' + view)).json()).groups[siteTaskId]?.map((r: { plant_id: string }) => r.plant_id) ?? []).not.toContain(laterPlantId);
  await request(`/api/plants/${laterPlantId}/restore`, 'POST', {});
  await undo(laterDone.event.id);
  await undo(done.event.id);
  await request('/api/custom-tasks/' + ownTask.id, 'DELETE');
});

test('validation refuses malformed JSON, invalid names and intervals, or changing a task target', async () => {
  const valid = { name: 'Test valid task', interval_days: 7, plant_id: plantId };
  const before = await (await request('/api/custom-tasks/' + plantTaskId)).json();
  for (const bad of [{ name: '' }, { name: ' \t ' }, { name: 'a'.repeat(61) }, { name: null }, { name: 1 }, { interval_days: 0 }, { interval_days: 366 }, { interval_days: 1.5 }, { interval_days: '7' }, { interval_days: null }]) {
    expect((await request('/api/custom-tasks', 'POST', { ...valid, ...bad })).status).toBe(422);
    expect((await request('/api/custom-tasks/' + plantTaskId, 'PUT', { ...valid, ...bad })).status).toBe(422);
  }
  for (const target of [{}, { plant_id: plantId, site_id: siteId }, { plant_id: 'bad' }, { site_id: 7 }, { plant_id: '' }]) expect((await request('/api/custom-tasks', 'POST', { name: 'Test task', interval_days: 7, ...target })).status).toBe(422);
  for (const [target, id] of [['plant_id', randomUUID()], ['site_id', randomUUID()]]) expect((await request('/api/custom-tasks', 'POST', { name: 'Test task', interval_days: 7, [target]: id })).status).toBe(404);
  expect((await request('/api/custom-tasks/' + plantTaskId, 'PUT', { name: 'Test task', interval_days: 7, plant_id: laterPlantId })).status).toBe(422);
  for (const body of [null, [], 'bad']) {
    expect((await request('/api/custom-tasks', 'POST', body)).status).toBe(400);
    expect((await request('/api/custom-tasks/' + plantTaskId, 'PUT', body)).status).toBe(400);
  }
  for (const id of [plantTaskId, siteTaskId]) {
    for (const request_id of [undefined, 3, 'bad']) expect((await request(`/api/plants/${plantId}/tasks/${id}/complete`, 'POST', { request_id })).status).toBe(422);
    for (const body of [null, [], 'bad']) expect((await request(`/api/plants/${plantId}/tasks/${id}/snooze`, 'POST', body)).status).toBe(400);
  }
  expect(await (await request('/api/custom-tasks/' + plantTaskId)).json()).toEqual(before);
  for (const target of [{ plant_id: plantId }, { site_id: siteId }]) {
    const max = await request('/api/custom-tasks', 'POST', { ...target, name: 'a'.repeat(60), interval_days: 365 });
    expect(max.status).toBe(201);
    const task = await max.json();
    expect((await request('/api/custom-tasks/' + task.id, 'PUT', { name: '  Test minimum  ', interval_days: 1 })).status).toBe(200);
    expect((await request('/api/custom-tasks/' + task.id, 'DELETE')).status).toBe(204);
  }
});

test('all custom endpoints refuse unauthenticated, wrong-origin and cross-household requests', async () => {
  const valid = { plant_id: plantId, name: 'Test valid task', interval_days: 7 };
  const done = await (await act(plantTaskId, 'complete')).json();
  const paths: [string, string, unknown?][] = [
    ['/api/custom-tasks?plant_id=' + plantId, 'GET'], ['/api/custom-tasks?site_id=' + siteId, 'GET'],
    ['/api/custom-tasks', 'POST', valid], ['/api/custom-tasks', 'POST', { site_id: siteId, name: 'Test site task', interval_days: 7 }],
    ['/api/custom-tasks/' + plantTaskId, 'GET'], ['/api/custom-tasks/' + siteTaskId, 'GET'],
    ['/api/custom-tasks/' + plantTaskId, 'PUT', valid], ['/api/custom-tasks/' + siteTaskId, 'PUT', { name: 'Test site task', interval_days: 7 }],
    ['/api/custom-tasks/' + plantTaskId, 'DELETE'], ['/api/custom-tasks/' + siteTaskId, 'DELETE'],
    ...['complete', 'snooze', 'skip'].flatMap(action => [plantTaskId, siteTaskId].map(id => [`/api/plants/${plantId}/tasks/${id}/${action}`, 'POST', { request_id: randomUUID() }] as [string, string, unknown])),
    [`/api/care-events/${done.event.id}/undo`, 'POST'],
  ];
  const { cookie } = await harness.login();
  for (const [path, method, body] of paths) {
    for (const session of ['', 'fytorium_session=invalid']) expect((await request(path, method, body, session)).status).toBe(401);
    if (method !== 'GET') expect((await fetch(harness.base + path, { method, headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })).status).toBe(403);
  }
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  await fixtureDb.query('DROP INDEX households_singleton');
  const household = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test other custom household') RETURNING id");
  await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-custom-other', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  const other = await harness.login('synthetic-custom-other');
  for (const [path, method, body] of paths) expect((await other.request(path, method, body)).status).toBe(404);
  const otherSite = await (await other.request('/api/sites', 'POST', { name: 'Test foreign custom site', location: 'indoor', light: 'part_sun', humidity: 'normal', draft: false, grow_light: false })).json();
  expect((await request('/api/custom-tasks', 'POST', { site_id: otherSite.id, name: 'Test foreign task', interval_days: 7 })).status).toBe(404);
  expect((await act(randomUUID(), 'complete')).status).toBe(404);
  await undo(done.event.id);
});

test('deleting an empty site keeps custom care history for its graveyard plants', async () => {
  const site = await (await request('/api/sites', 'POST', { name: 'Test retired custom site', location: 'indoor', light: 'part_sun', humidity: 'normal', draft: false, grow_light: false })).json();
  const plant = await addPlant(site.id, 'Test retired custom plant');
  const task = await (await request('/api/custom-tasks', 'POST', { site_id: site.id, name: 'Test retired site check', interval_days: 7 })).json();
  const done = await (await act(task.id, 'complete', plant)).json();
  await request(`/api/plants/${plant}/graveyard`, 'POST');
  expect((await request('/api/sites/' + site.id, 'DELETE')).status).toBe(204);
  expect(await (await request(`/api/plants/${plant}/history`)).json()).toContainEqual(expect.objectContaining({ id: done.event.id, custom_task_name: 'Test retired site check' }));
  expect((await request('/api/custom-tasks/' + task.id)).status).toBe(404);
  expect(await tasks(plant)).toEqual({});
  expect((await request(`/api/plants/${plant}`, 'DELETE', { confirm_name: 'Test retired custom plant' })).status).toBe(204);
});

test('deleting plant and site tasks removes them everywhere but keeps their names and events readable', async () => {
  for (const id of [plantTaskId, siteTaskId]) {
    await act(id, 'complete');
    expect((await request('/api/custom-tasks/' + id, 'DELETE')).status).toBe(204);
    expect((await request('/api/custom-tasks/' + id)).status).toBe(404);
    expect((await tasks()).customTasks[id]).toBeUndefined();
    expect((await tasks(laterPlantId)).customTasks[id]).toBeUndefined();
    expect(await (await request(`/api/plants/${plantId}/history`)).json()).toContainEqual(expect.objectContaining({ custom_task_id: id, custom_task_name: 'Test check roots', kind: 'completed' }));
    for (const view of ['today', 'upcoming']) expect((await (await request('/api/tasks?view=' + view)).json()).groups[id]).toBeUndefined();
    expect((await act(id, 'complete')).status).toBe(404);
  }
});

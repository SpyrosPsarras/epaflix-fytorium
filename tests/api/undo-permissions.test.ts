import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const dates = ['2030-05-30', '2030-05-31', '2030-06-01', '2030-06-02', '2030-06-03', '2030-06-04', '2030-06-05', '2030-06-06', '2030-06-07'];
const harness = createApiHarness({ testClock: true, queryStatistics: true, weather: () => ({ status: 200, body: { daily: {
  time: dates, temperature_2m_min: dates.map(() => 10), temperature_2m_max: dates.map(() => 20),
  precipitation_sum: dates.map(() => 6), daylight_duration: dates.map(() => 43200),
} } }) });
let owner: Awaited<ReturnType<typeof harness.login>>;
let plant: string;
let fixtureDb: pg.Pool;
afterAll(async () => { await fixtureDb?.end(); });
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T12:00:00Z' }) });
  owner = await harness.login();
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Undo garden', location: 'outdoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  plant = (await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname: 'Undo fern' })).json()).id;
  await owner.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
  await owner.request('/__test/weather-worker', 'POST');
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  await fixtureDb.query('CREATE EXTENSION pg_stat_statements');
});

test('history query count stays flat for 5 and 50 events', async () => {
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  const current = await (await owner.request(`/api/plants/${plant}`)).json();
  const added = await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: current.site_id,
    search_id: search.search_id, gbif_key: 910001, nickname: 'Query count fern' })).json();
  const calls = async () => Number((await fixtureDb.query('SELECT sum(calls) AS n FROM pg_stat_statements')).rows[0].n);
  const measure = async (size: number) => {
    const counts: number[] = [];
    for (let i = 0; i < 3; i++) {
      const before = await calls();
      const response = await owner.request(`/api/plants/${added.id}/history`);
      expect(response.status).toBe(200);
      const events = await response.json();
      expect(events).toHaveLength(size);
      expect(events.every((event: any) => event.can_undo)).toBe(true);
      counts.push(await calls() - before - 1);
    }
    return Math.min(...counts);
  };
  const counts: number[] = [];
  for (let i = 1; i <= 50; i++) {
    expect((await owner.request(`/api/plants/${added.id}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).status).toBe(200);
    if (i === 5 || i === 50) counts.push(await measure(i));
  }
  console.log(`History queries: 5 events = ${counts[0]}; 50 events = ${counts[1]}`);
  expect(counts[1]).toBe(counts[0]);
  expect(counts[0]).toBeLessThanOrEqual(10);
});

async function invite(subject: string, role: 'family' | 'sitter') {
  const created = await (await owner.request('/api/household/invites', 'POST', { role, plant_ids: role === 'sitter' ? [plant] : [] })).json();
  const member = await harness.login(subject, {}, created.link.split('/').at(-1));
  return member;
}
async function eventInViews(member: typeof owner, id: string, allowed: boolean) {
  for (const view of ['today', 'upcoming']) {
    const list = await (await member.request('/api/tasks?view=' + view)).json();
    const event = (Object.values(list.groups).flat() as any[]).find(row => row.completed_event?.id === id)?.completed_event;
    expect(event?.can_undo).toBe(allowed);
    expect(event).not.toHaveProperty('repot_flow');
  }
}
async function historyFlag(member: typeof owner, id: string, allowed: boolean) {
  const history = await (await member.request(`/api/plants/${plant}/history`)).json();
  expect(history.find((event: any) => event.id === id)?.can_undo).toBe(allowed);
}

test('missing and invalid undo IDs retain the web errors', async () => {
  const sitter = await invite('undo-status-sitter', 'sitter');
  for (const id of ['not-a-uuid', randomUUID()]) {
    for (const [path, message] of [['care-events', 'Care event not found'], ['task-batches', 'Task batch not found']]) {
      const response = await owner.request(`/api/${path}/${id}/undo`, 'POST');
      expect(response.status).toBe(404);
      expect(await response.json()).toMatchObject({ message });
      const hidden = await sitter.request(`/api/${path}/${id}/undo`, 'POST');
      expect(hidden.status).toBe(404);
      expect(await hidden.json()).toMatchObject({ message: 'Care action not found' });
    }
  }
});

for (const role of ['owner', 'family', 'sitter'] as const) test(`${role} receives the same undo permission the endpoint enforces`, async () => {
  let member = role === 'owner' ? owner : await invite('undo-' + role, 'family');
  await member.request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: true });
  if (role === 'sitter') {
    const id = (await (await member.request('/api/me')).json()).member.id;
    await owner.request('/api/household/members/' + id, 'DELETE');
    member = await invite('undo-' + role, 'sitter');
  }
  const done = await (await member.request(`/api/plants/${plant}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).json();
  expect(done.event.can_undo).toBe(true);
  await eventInViews(member, done.event.id, true);
  if (role !== 'sitter') await historyFlag(member, done.event.id, true);
  const undone = await member.request(`/api/care-events/${done.event.id}/undo`, 'POST');
  expect(undone.status).toBe(200);
  expect((await undone.json()).event.can_undo).toBe(false);
  if (role !== 'sitter') await historyFlag(member, done.event.id, false);
  const repeated = await member.request(`/api/care-events/${done.event.id}/undo`, 'POST');
  expect(repeated.status).toBe(409);
  expect(await repeated.json()).toMatchObject({ message: 'Care event already undone' });

  const batch = await (await member.request(`/api/plants/${plant}/tasks/complete-all`, 'POST', { request_id: randomUUID() })).json();
  expect(batch.events.length).toBeGreaterThan(0);
  expect(batch.events.every((event: any) => event.can_undo)).toBe(true);
  const singleBatchUndo = await member.request(`/api/care-events/${batch.events[0].id}/undo`, 'POST');
  expect(singleBatchUndo.status).toBe(409);
  expect(await singleBatchUndo.json()).toMatchObject({ message: 'Undo this care action as one batch.' });
  expect((await member.request(`/api/task-batches/${batch.events[0].batch_id}/undo`, 'POST')).status).toBe(200);
  const repeatedBatch = await member.request(`/api/task-batches/${batch.events[0].batch_id}/undo`, 'POST');
  expect(repeatedBatch.status).toBe(409);
  expect(await repeatedBatch.json()).toMatchObject({ message: 'Task batch already undone' });
  if (role !== 'sitter') await historyFlag(member, batch.events[0].id, false);
  if (role === 'sitter') {
    const other = await (await owner.request(`/api/plants/${plant}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).json();
    await eventInViews(member, other.event.id, false);
    expect((await member.request(`/api/care-events/${other.event.id}/undo`, 'POST')).status).toBe(403);
    await owner.request(`/api/care-events/${other.event.id}/undo`, 'POST');
  }
});

test('a former family member cannot undo their own rain batch as a sitter', async () => {
  const family = await invite('undo-rain-former-family', 'family');
  const memberId = (await (await family.request('/api/me')).json()).member.id;
  await family.request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: true });
  const rainResponse = await family.request('/api/weather/rain/water', 'POST', { request_id: randomUUID() });
  expect(rainResponse.status).toBe(200);
  const rain = await rainResponse.json();
  const history = await (await family.request(`/api/plants/${plant}/history`)).json();
  const event = history.find((event: any) => event.batch_id === rain.batchId);
  expect(event).toMatchObject({ by_member: memberId, can_undo: true });
  await eventInViews(family, event.id, true);
  await owner.request(`/api/household/members/${memberId}`, 'DELETE');
  const sitter = await invite('undo-rain-former-family', 'sitter');
  await eventInViews(sitter, event.id, false);
  const denied = await sitter.request(`/api/task-batches/${rain.batchId}/undo`, 'POST');
  expect(denied.status).toBe(404);
  expect(await denied.json()).toMatchObject({ message: 'Care action not found' });
  await historyFlag(owner, event.id, true);
  expect((await owner.request(`/api/task-batches/${rain.batchId}/undo`, 'POST')).status).toBe(200);
  await historyFlag(owner, event.id, false);
});

test('repot ordering, edited pot fields and graveyard state disable undo', async () => {
  const repot = async (size: number) => (await (await owner.request(`/api/plants/${plant}/repot`, 'POST', {
    request_id: randomUUID(), pot_type: 'terracotta', pot_size_cm: size, soil_type: 'all_purpose',
  })).json()).event;
  const first = await repot(20);
  expect(first.can_undo).toBe(true);
  const second = await repot(22);
  await historyFlag(owner, first.id, false);
  const laterRepot = await owner.request(`/api/care-events/${first.id}/undo`, 'POST');
  expect(laterRepot.status).toBe(409);
  expect(await laterRepot.json()).toMatchObject({ message: 'Undo the later repot first.' });
  expect((await owner.request(`/api/care-events/${second.id}/undo`, 'POST')).status).toBe(200);
  await historyFlag(owner, first.id, true);
  const current = await (await owner.request(`/api/plants/${plant}`)).json();
  expect((await owner.request(`/api/plants/${plant}`, 'PUT', { ...current, pot_type: 'plastic' })).status).toBe(200);
  await historyFlag(owner, first.id, false);
  const editedPot = await owner.request(`/api/care-events/${first.id}/undo`, 'POST');
  expect(editedPot.status).toBe(409);
  expect(await editedPot.json()).toMatchObject({ message: 'Pot fields changed after this repot. Undo would overwrite those edits.' });
  const batch = await (await owner.request(`/api/plants/${plant}/tasks/complete-all`, 'POST', { request_id: randomUUID() })).json();
  await owner.request(`/api/plants/${plant}/graveyard`, 'POST');
  const graveyardBatch = await owner.request(`/api/task-batches/${batch.events[0].batch_id}/undo`, 'POST');
  expect(graveyardBatch.status).toBe(409);
  expect(await graveyardBatch.json()).toMatchObject({ message: 'Restore graveyard plants before undoing their care.' });
  await historyFlag(owner, first.id, false);
  const graveyardUndo = await owner.request(`/api/care-events/${first.id}/undo`, 'POST');
  expect(graveyardUndo.status).toBe(409);
  expect(await graveyardUndo.json()).toMatchObject({ message: 'Graveyard plants have no tasks. Restore the plant first.' });
});

test('complete-type admits family but refuses sitters and hides their former household-wide batch undo', async () => {
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-20T12:00:00Z' });
  owner = await harness.login();
  expect((await owner.request(`/api/plants/${plant}/restore`, 'POST', {})).status).toBe(200);
  const family = await invite('undo-type-former-family', 'family');
  await family.request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: true });
  const memberId = (await (await family.request('/api/me')).json()).member.id;
  const response = await family.request('/api/tasks/water/complete-all', 'POST', { request_id: randomUUID() });
  expect(response.status).toBe(200);
  const result = await response.json();
  const event = result.events.find((event: any) => event.plant_id === plant);
  expect(event).toMatchObject({ by_member: memberId, batch_id: result.batch_id, can_undo: true });
  await historyFlag(family, event.id, true);
  await owner.request(`/api/household/members/${memberId}`, 'DELETE');
  const sitter = await invite('undo-type-former-family', 'sitter');
  for (const type of ['water', 'nope']) {
    const denied = await sitter.request(`/api/tasks/${type}/complete-all`, 'POST', { request_id: randomUUID() });
    expect(denied.status).toBe(403);
  }
  await eventInViews(sitter, event.id, false);
  const denied = await sitter.request(`/api/task-batches/${result.batch_id}/undo`, 'POST');
  expect(denied.status).toBe(404);
  expect(await denied.json()).toMatchObject({ message: 'Care action not found' });
  await historyFlag(owner, event.id, true);
  expect((await owner.request(`/api/task-batches/${result.batch_id}/undo`, 'POST')).status).toBe(200);
  await historyFlag(owner, event.id, false);
});

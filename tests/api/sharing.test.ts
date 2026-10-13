import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
import { randomUUID } from 'node:crypto';
const harness = createApiHarness({ testClock: true });
let owner: Awaited<ReturnType<typeof harness.login>>;
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T07:00:00Z' }) });
  owner = await harness.login();
});
test('an owner invite admits a new OIDC subject once with its chosen role', async () => {
  const response = await owner.request('/api/household/invites', 'POST', { role: 'family', plant_ids: [] });
  expect(response.status).toBe(201);
  const invite = await response.json();
  const token = invite.link.split('/').at(-1);
  const family = await harness.login('invited-family', { name: 'Family member' }, token);
  expect(family.callback.headers.get('location')).toBe('/');
  expect(await (await family.request('/api/me')).json()).toMatchObject({ member: { role: 'family' } });
  const reused = await harness.login('replay-subject', {}, token);
  expect(reused.callback.headers.get('location')).toBe('/auth/not-member');
  const unknown = await harness.login('unknown-subject');
  expect(unknown.callback.headers.get('location')).toBe('/auth/not-member');
  expect(harness.appOutput).not.toContain(token);
  expect(harness.appOutput).not.toContain(invite.link);
});

let shared: string, privatePlant: string;
let sitter: Awaited<ReturnType<typeof harness.login>>;
let sitterId: string;
test('sitters see only shared active plants in Today, Upcoming and the basic page, and can undo only their own tasks', async () => {
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T07:00:00Z' });
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Sharing room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  const add = async (nickname: string) => (await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname })).json()).id;
  shared = await add('Shared fern'); privatePlant = await add('Private fern');
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [shared] })).json();
  sitter = await harness.login('invited-sitter', { name: 'Sitter member' }, invite.link.split('/').at(-1));
  sitterId = (await (await sitter.request('/api/me')).json()).member.id;
  for (const view of ['today', 'upcoming']) {
    const list = await (await sitter.request('/api/tasks?view=' + view)).json();
    expect([...new Set(Object.values(list.groups).flat().map((task: any) => task.plant_id))]).toEqual([shared]);
  }
  const basic = await sitter.request('/api/plants/' + shared);
  expect(basic.status).toBe(200);
  expect(await basic.json()).not.toHaveProperty('cover_photo_id');
  expect((await sitter.request('/plants/' + shared)).status).toBe(200);
  expect((await sitter.request('/api/plants/' + privatePlant)).status).toBe(404);
  expect((await sitter.request('/plants/' + privatePlant)).status).toBe(404);
  expect((await sitter.request('/api/plants/' + shared + '/history')).status).toBe(403);
  const own = await (await sitter.request(`/api/plants/${shared}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).json();
  expect((await sitter.request('/api/care-events/' + own.event.id + '/undo', 'POST')).status).toBe(200);
  const other = await (await owner.request(`/api/plants/${shared}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).json();
  expect((await sitter.request('/api/care-events/' + other.event.id + '/undo', 'POST')).status).toBe(403);
  await owner.request('/api/care-events/' + other.event.id + '/undo', 'POST');
});

let returning: Awaited<ReturnType<typeof harness.login>>;
test('a fresh invite reactivates a removed subject with its new role and shares, but never restores old sessions or devices', async () => {
  const create = async (role: string, plant_ids: string[] = []) => (await (await owner.request('/api/household/invites', 'POST', { role, plant_ids })).json());
  const firstInvite = await create('family');
  const first = await harness.login('returning-sitter', {}, firstInvite.link.split('/').at(-1));
  const memberId = (await (await first.request('/api/me')).json()).member.id;
  await first.request('/api/tasks/settings', 'PUT', { sort: 'site', filter: 'all', show_completed: true });
  await first.request('/api/notifications/subscriptions', 'POST', harness.push.subscribe());
  expect((await first.request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled: false, completion_notices_enabled: true })).status).toBe(200);
  expect((await owner.request('/api/household/members/' + memberId, 'DELETE')).status).toBe(204);
  for (const token of [undefined, 'x'.repeat(43), firstInvite.link.split('/').at(-1)]) {
    expect((await harness.login('returning-sitter', {}, token)).callback.headers.get('location')).toBe('/auth/not-member');
  }
  const revoked = await create('sitter', [privatePlant]);
  await owner.request('/api/household/invites/' + revoked.id, 'DELETE');
  expect((await harness.login('returning-sitter', {}, revoked.link.split('/').at(-1))).callback.headers.get('location')).toBe('/auth/not-member');
  const holiday = await create('sitter', [shared]);
  returning = await harness.login('returning-sitter', {}, holiday.link.split('/').at(-1));
  expect(returning.callback.headers.get('location')).toBe('/');
  expect(await (await returning.request('/api/me')).json()).toMatchObject({ member: { id: memberId, role: 'sitter' } });
  expect((await first.request('/api/me')).status).toBe(401);
  expect(await (await returning.request('/api/notifications/subscriptions')).json()).toEqual([]);
  expect(await (await returning.request('/api/notifications/settings')).json()).toMatchObject({ completion_notices_enabled: false });
  expect((await returning.request('/api/plants/' + shared)).status).toBe(200);
  expect((await returning.request('/api/plants/' + privatePlant)).status).toBe(404);
  expect((await harness.login('holiday-replay', {}, holiday.link.split('/').at(-1))).callback.headers.get('location')).toBe('/auth/not-member');
  expect((await harness.login('returning-sitter')).callback.headers.get('location')).toBe('/');
  const familyHoliday = await create('family');
  // An invite cannot change an active member, and the failed attempt must not consume it.
  expect((await harness.login('returning-sitter', {}, familyHoliday.link.split('/').at(-1))).callback.headers.get('location')).toBe('/auth/not-member');
  await owner.request('/api/household/members/' + memberId, 'DELETE');
  const familyAgain = await harness.login('returning-sitter', {}, familyHoliday.link.split('/').at(-1));
  expect(await (await familyAgain.request('/api/me')).json()).toMatchObject({ member: { id: memberId, role: 'family' } });
  expect((await familyAgain.request('/api/plants/' + privatePlant)).status).toBe(200);
  await owner.request('/api/household/members/' + memberId, 'DELETE');
  const finalHoliday = await create('sitter', [shared]);
  returning = await harness.login('returning-sitter', {}, finalHoliday.link.split('/').at(-1));
  expect((await returning.request('/api/plants/' + privatePlant)).status).toBe(404);
});

test('sitter Today and Upcoming page data and task APIs contain only the public projection, including completed repots', async () => {
  const repot = await (await owner.request(`/api/plants/${shared}/repot`, 'POST', { request_id: randomUUID(), pot_type: 'terracotta', pot_size_cm: 20, soil_type: 'all_purpose' })).json();
  expect(repot.event.previous_pot).not.toBeNull();
  const eventFields = ['id', 'plant_id', 'task_type', 'custom_task_id', 'custom_task_name', 'kind', 'occurred_at', 'occurred_on', 'batch_id', 'by_member', 'by_name', 'undone_at', 'undone_by', 'request_id', 'can_undo'];
  const taskFields = ['status', 'dueOn', 'intervalDays', 'lateDays', 'basedOn', 'name', 'custom_task_id', 'custom_task_name', 'task_type', 'plant_id', 'plant_name', 'site_name', 'completed_event'];
  const only = (value: Record<string, unknown>, fields: string[]) => expect(Object.keys(value).filter(key => !fields.includes(key))).toEqual([]);
  const event = (value: Record<string, unknown>) => only(value, eventFields);
  const task = (value: any) => {
    only(value, taskFields);
    expect(value.basedOn).toEqual([]);
    if ('site_name' in value) expect(value.site_name).toBe('');
    if (value.completed_event) event(value.completed_event);
  };
  const list = (value: any) => {
    only(value, ['today', 'view', 'settings', 'groups', 'sections']);
    only(value.settings, ['sort', 'filter', 'show_completed']);
    expect(value.settings.sort).toBe('task');
    const rows = Object.values(value.groups).flat() as any[];
    expect(rows.some(row => row.completed_event?.id === repot.event.id)).toBe(true);
    rows.forEach(task);
    for (const section of value.sections) {
      only(section, ['key', 'label', 'tasks']);
      expect(section.label).not.toBe('Sharing room');
      section.tasks.forEach(task);
    }
  };
  // SvelteKit's data endpoint encodes objects as references into each node's data array.
  const pageNode = (node: any) => {
    const decode = (index: number): any => {
      if (index < 0) return undefined;
      const value = node.data[index];
      if (Array.isArray(value)) return value[0] === 'Date' ? value[1] : value.map(decode);
      if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, ref]) => [key, decode(ref as number)]));
      return value;
    };
    return decode(0);
  };
  for (const [path, view] of [['/', 'today'], ['/upcoming', 'upcoming']]) {
    list(await (await returning.request('/api/tasks?view=' + view)).json());
    const response = await returning.request((path === '/' ? path : path + '/') + '__data.json');
    expect(response.status).toBe(200);
    const body = await response.json();
    const data = pageNode(body.nodes.find((node: any) => node?.type === 'data'));
    only(data, path === '/' ? ['identity', 'plantCount', 'tasks', 'feed'] : ['identity', 'tasks']);
    if (path === '/') expect(Object.keys(data.feed)).toEqual(['cards']);
    list(data.tasks);
  }
  const plantFields = ['id', 'display_name', 'nickname', 'scientific_name', 'canonical_name', 'cultivar', 'birthday', 'status'];
  only(await (await returning.request('/api/plants/' + shared)).json(), plantFields);
  const plantPage = await (await returning.request('/plants/' + shared + '/__data.json')).json();
  only(pageNode(plantPage.nodes.find((node: any) => node?.data?.[0]?.plant !== undefined)).plant, plantFields);
  const schedule = await (await returning.request('/api/plants/' + shared + '/tasks')).json();
  only(schedule, ['water', 'fertilize', 'mist', 'clean_leaves', 'remove_dead', 'cut_back', 'repot', 'customTasks']);
  Object.entries(schedule).forEach(([key, value]) => key === 'customTasks' ? Object.values(value as object).forEach(task) : task(value));
  const completion = await (await returning.request(`/api/plants/${shared}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).json();
  only(completion, ['event', 'task']); event(completion.event); task(completion.task);
  const undone = await (await returning.request('/api/care-events/' + completion.event.id + '/undo', 'POST')).json();
  only(undone, ['event', 'task']); event(undone.event); task(undone.task);
  const batch = await (await returning.request(`/api/plants/${shared}/tasks/complete-all`, 'POST', { request_id: randomUUID() })).json();
  only(batch, ['events', 'tasks']); batch.events.forEach(event);
  const batchUndo = await (await returning.request('/api/task-batches/' + batch.events[0].batch_id + '/undo', 'POST')).json();
  only(batchUndo, ['batchId', 'undoneAt']);
  await owner.request('/api/care-events/' + repot.event.id + '/undo', 'POST');
});

test('a former family member cannot undo their own repot after returning as a sitter', async () => {
  const familyInvite = await (await owner.request('/api/household/invites', 'POST', { role: 'family', plant_ids: [] })).json();
  const family = await harness.login('synthetic-a04-former-family', {}, familyInvite.link.split('/').at(-1));
  const memberId = (await (await family.request('/api/me')).json()).member.id;
  await family.request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: true });
  const repot = await (await family.request(`/api/plants/${shared}/repot`, 'POST', { request_id: randomUUID(), pot_type: 'plastic', pot_size_cm: 18, soil_type: 'all_purpose' })).json();
  expect(repot.event.previous_pot).not.toBeNull();
  await owner.request('/api/household/members/' + memberId, 'DELETE');
  const sitterInvite = await (await owner.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [shared] })).json();
  const changed = await harness.login('synthetic-a04-former-family', {}, sitterInvite.link.split('/').at(-1));
  for (const view of ['today', 'upcoming']) {
    const list = await (await changed.request('/api/tasks?view=' + view)).json();
    const event = (Object.values(list.groups).flat() as any[]).find(row => row.completed_event?.id === repot.event.id)?.completed_event;
    expect(event).toMatchObject({ by_member: memberId, can_undo: false });
    expect(event).not.toHaveProperty('previous_pot');
  }
  expect((await changed.request('/api/care-events/' + repot.event.id + '/undo', 'POST')).status).toBe(403);
  expect((await owner.request('/api/care-events/' + repot.event.id + '/undo', 'POST')).status).toBe(200);
});

test('unlisted pages fail closed and authorization metrics retain the response status', async () => {
  for (const member of [owner, sitter]) {
    expect((await member.request('/future-private-page')).status).toBe(404);
    expect((await member.request('/api/future-private')).status).toBe(404);
  }
  expect((await fetch(harness.base + '/future-private-page')).status).toBe(404);
  expect((await sitter.request('/settings')).status).toBe(403);
  expect((await sitter.request('/plants/' + privatePlant)).status).toBe(404);
  expect((await fetch(harness.base + '/api/me')).status).toBe(401);
  expect((await fetch(harness.base + '/upcoming', { redirect: 'manual' })).status).toBe(303);
  const metrics = await (await fetch(harness.base + '/metrics')).text();
  for (const [route, status] of [['/settings', '403'], ['/plants/[id]', '404'], ['/api/me', '401'], ['/upcoming', '303']]) {
    expect(metrics).toContain(`route="${route}",method="GET",status="${status}"`);
    expect(metrics).not.toContain(`route="${route}",method="GET",status="500"`);
  }
});

test('daily push and completion notices use sitter shares, opt-in and one leased delivery per event and member', async () => {
  const familyInvite = await (await owner.request('/api/household/invites', 'POST', { role: 'family' })).json();
  const family = await harness.login('push-family', {}, familyInvite.link.split('/').at(-1));
  // Enabling notices later must not replay completions recorded while they were off.
  await owner.request(`/api/plants/${privatePlant}/tasks/clean_leaves/complete`, 'POST', { request_id: randomUUID() });
  const devices: Record<string, string> = {};
  for (const [member, label] of [[owner, 'actor'], [family, 'family'], [sitter, 'sitter']] as const) {
    const subscription = harness.push.subscribe(); devices[label] = subscription.endpoint;
    expect((await member.request('/api/notifications/subscriptions', 'POST', { ...subscription, label })).status).toBe(201);
    expect((await member.request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: label === 'sitter', weather_alerts_enabled: false, completion_notices_enabled: true })).status).toBe(200);
  }
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received.map(row => row.payload.body)).toEqual(['1 task today · 0 late · Shared fern']);
  harness.push.received.length = 0;
  const event = await (await owner.request(`/api/plants/${shared}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).json();
  await Promise.all([owner.request('/__test/push-worker', 'POST'), owner.request('/__test/push-worker', 'POST')]);
  expect(harness.push.received).toHaveLength(2);
  expect(harness.push.received.map(row => row.endpoint).sort()).toEqual([devices.family, devices.sitter].sort());
  expect(harness.push.received.map(row => row.payload)).toEqual([expect.objectContaining({ tag: 'fytorium-completion-' + event.event.id, body: 'Water completed · Shared fern' }), expect.objectContaining({ tag: 'fytorium-completion-' + event.event.id, body: 'Water completed · Shared fern' })]);
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(2);
  await owner.request(`/api/plants/${privatePlant}/tasks/water/complete`, 'POST', { request_id: randomUUID() });
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(3);
  expect(harness.push.received[2].endpoint).toBe(devices.family);
  await family.request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled: false, completion_notices_enabled: false });
  await owner.request(`/api/plants/${privatePlant}/tasks/water/complete`, 'POST', { request_id: randomUUID() });
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(3);
  await owner.request(`/api/plants/${shared}/tasks/water/snooze`, 'POST', { request_id: randomUUID() });
  await owner.request(`/api/plants/${shared}/tasks/water/skip`, 'POST', { request_id: randomUUID() });
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(3);
});

test('completion delivery retries only after its lease, survives restart, and rechecks sitter shares', async () => {
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'family' })).json();
  const member = await harness.login('retry-family', {}, invite.link.split('/').at(-1));
  const sub = harness.push.subscribe();
  await member.request('/api/notifications/subscriptions', 'POST', sub);
  await member.request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled: false, completion_notices_enabled: true });
  harness.push.respond(sub.endpoint, 503);
  const event = await (await owner.request(`/api/plants/${privatePlant}/tasks/water/complete`, 'POST', { request_id: randomUUID() })).json();
  const before = harness.push.received.length;
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(before);
  harness.push.respond(sub.endpoint, 201);
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(before);
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 300001 });
  await Promise.all([owner.request('/__test/push-worker', 'POST'), owner.request('/__test/push-worker', 'POST')]);
  expect(harness.push.received.slice(before)).toEqual([expect.objectContaining({ endpoint: sub.endpoint, payload: expect.objectContaining({ tag: 'fytorium-completion-' + event.event.id }) })]);
  await harness.restartApp();
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T07:06:00Z' }) });
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(before + 1);
  await owner.request(`/api/plants/${shared}/tasks/water/complete`, 'POST', { request_id: randomUUID() });
  await owner.request('/api/household/members/' + sitterId, 'PUT', { plant_ids: [] });
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received.slice(before + 1).map(row => row.endpoint)).toEqual([sub.endpoint]);
  await member.request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: false, weather_alerts_enabled: false, completion_notices_enabled: false });
});

test('revoked, expired, invalid and concurrently reused invites cannot admit a subject', async () => {
  const create = async () => (await (await owner.request('/api/household/invites', 'POST', { role: 'family' })).json());
  const revoked = await create();
  expect((await owner.request('/api/household/invites/' + revoked.id, 'DELETE')).status).toBe(204);
  expect((await harness.login('revoked-subject', {}, revoked.link.split('/').at(-1))).callback.headers.get('location')).toBe('/auth/not-member');
  const expired = await create();
  const returningId = (await (await returning.request('/api/me')).json()).member.id;
  await owner.request('/api/household/members/' + returningId, 'DELETE');
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 7 * 86400000 });
  expect((await harness.login('expired-subject', {}, expired.link.split('/').at(-1))).callback.headers.get('location')).toBe('/auth/not-member');
  expect((await harness.login('returning-sitter', {}, expired.link.split('/').at(-1))).callback.headers.get('location')).toBe('/auth/not-member');
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T07:00:00Z' }) });
  owner = await harness.login();
  const race = await create();
  const token = race.link.split('/').at(-1);
  // OIDC's test provider chooses a subject per authorization, so race the same invited subject.
  const results = await Promise.all([harness.login('race-subject', {}, token), harness.login('race-subject', {}, token)]);
  expect(results.map(result => result.callback.headers.get('location')).sort()).toEqual(['/', '/auth/not-member']);
  expect((await harness.login('invalid-subject', {}, 'x'.repeat(43))).callback.headers.get('location')).toBe('/auth/not-member');
  expect((await (await owner.request('/api/household/invites')).json()).some((invite: any) => 'link' in invite || 'token_hash' in invite)).toBe(false);
});

test('changing shares, graveyard and deletion immediately remove sitter visibility and daily reminders', async () => {
  sitter = await harness.login('invited-sitter');
  expect((await owner.request('/api/household/members/' + sitterId, 'PUT', { plant_ids: [privatePlant] })).status).toBe(200);
  expect((await sitter.request('/api/plants/' + shared)).status).toBe(404);
  expect((await sitter.request('/api/plants/' + privatePlant)).status).toBe(200);
  await owner.request('/api/household/members/' + sitterId, 'PUT', { plant_ids: [shared] });
  await owner.request('/api/plants/' + shared + '/graveyard', 'POST');
  for (const view of ['today', 'upcoming']) expect(Object.values((await (await sitter.request('/api/tasks?view=' + view)).json()).groups).flat()).toEqual([]);
  expect((await sitter.request('/plants/' + shared)).status).toBe(404);
  const before = harness.push.received.length;
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 86400000 });
  await owner.request('/__test/push-worker', 'POST');
  expect(harness.push.received).toHaveLength(before);
  await owner.request('/api/plants/' + shared + '/restore', 'POST', {});
  expect((await owner.request('/api/plants/' + shared, 'DELETE', { confirm_name: 'Shared fern' })).status).toBe(204);
  expect((await sitter.request('/api/plants/' + shared)).status).toBe(404);
});

test('removal revokes every session and deletes devices, but preserves named care history', async () => {
  const addedDevice = harness.push.subscribe();
  expect((await sitter.request('/api/notifications/subscriptions', 'POST', addedDevice)).status).toBe(201);
  const secondSession = await harness.login('invited-sitter');
  const self = (await (await owner.request('/api/me')).json()).member.id;
  expect((await owner.request('/api/household/members/' + self, 'DELETE')).status).toBe(409);
  await owner.request('/api/household/members/' + sitterId, 'PUT', { plant_ids: [privatePlant] });
  const done = await sitter.request(`/api/plants/${privatePlant}/tasks/water/complete`, 'POST', { request_id: randomUUID() });
  expect(done.status).toBe(200);
  expect((await owner.request('/api/household/members/' + sitterId, 'DELETE')).status).toBe(204);
  expect((await sitter.request('/api/me')).status).toBe(401);
  expect((await secondSession.request('/api/tasks')).status).toBe(401);
  expect((await harness.login('invited-sitter')).callback.headers.get('location')).toBe('/auth/not-member');
  expect((await owner.request('/api/notifications/subscriptions', 'POST', addedDevice)).status).toBe(201);
  const history = await (await owner.request('/api/plants/' + privatePlant + '/history')).json();
  expect(history).toContainEqual(expect.objectContaining({ by_member: sitterId, by_name: 'Test member' }));
});

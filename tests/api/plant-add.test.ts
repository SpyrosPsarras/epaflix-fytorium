import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { fixturePlant } from '../plant-fixture';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true, wiremock: true, appEnv: { HOUSEHOLD_TIMEZONE: 'America/Los_Angeles' } });
let request: Awaited<ReturnType<typeof harness.login>>['request'];
let database: pg.Pool;
let site_id: string;
let memberId: string;
const today = '2026-11-14';
const instant = '2026-11-15T02:00:00.000Z';
const unset = { pot_type: null, soil_type: null, plant_size: null, growth_stage: null, fertilizer_type: null, drainage: null };
const body = async (path: string) => (await request(path)).json();
const add = (input: Record<string, unknown>) => request('/api/plants', 'POST', { ...fixturePlant, site_id, gbif_key: 930030, confirmed: true, ...input });
const search = (name = 'Testoria conferta') => body('/api/species/search?' + new URLSearchParams({ q: name }));
const clock = (at: string) => request('/__test/clock', 'POST', { action: 'set', at });
const undo = (event: string) => request(`/api/care-events/${event}/undo`, 'POST');

beforeAll(async () => {
  ({ request } = await harness.login());
  expect((await clock(instant)).status).toBe(200);
  ({ request } = await harness.login());
  ({ id: memberId } = (await body('/api/me')).member);
  const site = await request('/api/sites', 'POST', { name: 'Test room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false });
  expect(site.status).toBe(201);
  ({ id: site_id } = await site.json());
  database = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  await database.query(`INSERT INTO members (household_id, issuer, subject, role, display_name, completion_notices_enabled)
    SELECT household_id, issuer, 'synthetic-care-recipient', 'family', 'Test recipient', true FROM members WHERE id = $1`, [memberId]);
});
afterAll(async () => { await database?.end(); });

test('accepts a confirmed species ten minutes after its receipt expired, using GBIF taxonomy', async () => {
  const { search_id } = await search();
  expect(typeof search_id).toBe('string');
  expect((await request('/__test/clock', 'POST', { action: 'advance', milliseconds: 600000 })).status).toBe(200);
  const response = await add({ search_id, scientific_name: 'Untrusted client taxonomy' });
  expect(response.status).toBe(201);
  expect(await response.json()).toMatchObject({ gbif_key: 930030, scientific_name: 'Testoria conferta Inventor' });
  expect((await add({ confirmed: false, search_id })).status).toBe(422);
  expect((await clock(instant)).status).toBe(200);
});

test('accepts confirmed species on create and change after an app restart clears receipts', async () => {
  const original = await search();
  const changed = await search('Testoria fugax');
  expect(typeof changed.search_id).toBe('string');
  await harness.restartApp();
  expect((await clock(instant)).status).toBe(200);
  ({ request } = await harness.login());
  const response = await add({ search_id: original.search_id });
  expect(response.status).toBe(201);
  const { id } = await response.json();
  const result = await request(`/api/plants/${id}/species`, 'PUT', { search_id: changed.search_id, gbif_key: 930031, confirmed: true });
  expect(result.status).toBe(200);
  expect(await result.json()).toMatchObject({ gbif_key: 930031, scientific_name: 'Testoria fugax Inventor' });
  const stored = await request(`/api/plants/${id}/species`, 'PUT', { gbif_key: 930030, confirmed: true });
  expect(stored.status).toBe(200);
  expect(await stored.json()).toMatchObject({ gbif_key: 930030 });
});

test('refuses unknown, non-plant, genus and doubtful keys without a live receipt', async () => {
  const before = await body('/api/plants?count=true');
  for (const gbif_key of [930099, 930032, 930033, 930034]) {
    const response = await add({ gbif_key });
    expect(response.status).toBe(422);
    expect(await response.json()).toMatchObject({ message: 'Search again before choosing this species.' });
  }
  expect(await body('/api/plants?count=true')).toEqual(before);
});

test('records last watering at household midnight, moves the due date, and restores it on Undo', async () => {
  const { search_id } = await search();
  const response = await add({ search_id, last_watered_on: '2026-11-11' });
  expect(response.status).toBe(201);
  const { id } = await response.json();
  const path = `/api/plants/${id}`;
  const events = await body(path + '/history');
  expect(events).toEqual([expect.objectContaining({ kind: 'completed', task_type: 'water', occurred_on: '2026-11-11',
    occurred_at: '2026-11-11T08:00:00.000Z', due_on: today, by_member: memberId, by_name: 'Test member', request_id: expect.any(String), can_undo: true })]);
  const { water } = await body(path + '/tasks');
  expect(water.dueOn).toBe(new Date(Date.UTC(2026, 10, 11 + water.intervalDays)).toISOString().slice(0, 10));
  expect((await database.query('SELECT 1 FROM completion_notice_sends WHERE event_id = $1', [events[0].id])).rowCount).toBe(0);
  const undone = await undo(events[0].id);
  expect(undone.status).toBe(200);
  expect(await undone.json()).toMatchObject({ task: { dueOn: today } });
});

test('records a watering for household today at the current time', async () => {
  const { search_id } = await search();
  const response = await add({ search_id, last_watered_on: today });
  expect(response.status).toBe(201);
  const { id } = await response.json();
  expect(await body(`/api/plants/${id}/history`)).toEqual([expect.objectContaining({ occurred_on: today, occurred_at: instant })]);
  const tasks = await body('/api/tasks?view=today');
  expect(tasks.groups.water).toContainEqual(expect.objectContaining({ plant_id: id, completed_event: expect.objectContaining({ kind: 'completed' }) }));
});

test('validates last watering before making any plant or history writes', async () => {
  const { search_id } = await search();
  const before = await body('/api/plants?count=true');
  for (const last_watered_on of ['2026-11-15', '2025-11-13', '2026-02-30', '2026-2-02', null, 20261111]) {
    const response = await add({ search_id, last_watered_on });
    expect(response.status, String(last_watered_on)).toBe(422);
    expect(await response.json()).toEqual({ errors: { last_watered_on: 'Choose a date in the last year, not in the future.' } });
  }
  const response = await add({ search_id, last_watered_on: '2026-11-11', birthday: '2026-11-12' });
  expect(response.status).toBe(422);
  expect(await response.json()).toEqual({ errors: { last_watered_on: 'Choose a date on or after the birthday.' } });
  expect(await body('/api/plants?count=true')).toEqual(before);
  const bound = await add({ search_id, last_watered_on: '2025-11-14', birthday: '2025-11-14' });
  expect(bound.status).toBe(201);
});

test('omitting optional plant attributes stores null and does not record watering', async () => {
  const { search_id } = await search();
  const response = await request('/api/plants', 'POST', { site_id, search_id, gbif_key: 930030, grow_light: false, near_ac: false, near_heater: false });
  expect(response.status).toBe(201);
  const plant = await response.json();
  expect(plant).toMatchObject(unset);
  const page = await request(`/plants/${plant.id}`);
  expect(page.status).toBe(200);
  expect(/Drainage<\/dt>\s*<dd\b[^>]*>Not set<\/dd>/.test((await page.text()).replace(/<!--[\s\S]*?-->/g, ''))).toBe(true);
  expect(await body(`/api/plants/${plant.id}/history`)).toEqual([]);
  expect(await body(`/api/plants/${plant.id}/tasks`)).toMatchObject({ water: { dueOn: today } });
  const cards = (await body('/api/feed')).cards;
  const warnings = cards.find((card: { plantId?: string }) => card.plantId === plant.id)?.warnings;
  expect(warnings).toContainEqual(expect.objectContaining({ kind: 'missing_info', reason: expect.stringContaining('pot type') }));
  expect(warnings).not.toContainEqual(expect.objectContaining({ kind: 'no_drainage' }));
});

test('explicit nulls survive editing, repot and Undo, and unknown fertilizer does not also record Water', async () => {
  const { search_id } = await search();
  const created = await add({ search_id });
  expect(created.status).toBe(201);
  const { id } = await created.json();
  const path = `/api/plants/${id}`;
  const edited = await request(path, 'PUT', { ...fixturePlant, site_id, ...unset });
  expect(edited.status).toBe(200);
  expect(await edited.json()).toMatchObject(unset);
  const pot = { pot_type: 'terracotta', pot_size_cm: 20, soil_type: 'cactus' };
  const repotted = await request(path + '/repot', 'POST', { ...pot, request_id: randomUUID() });
  expect(repotted.status).toBe(200);
  expect(await body(path)).toMatchObject(pot);
  expect((await undo((await repotted.json()).event.id)).status).toBe(200);
  expect(await body(path)).toMatchObject(unset);
  const fertilizer = await request(path + '/tasks/fertilize/complete', 'POST', { request_id: randomUUID() });
  expect(fertilizer.status).toBe(200);
  expect(await body(path + '/history')).toEqual(expect.arrayContaining([expect.objectContaining({ task_type: 'fertilize', kind: 'completed' })]));
  expect(await body(path + '/history')).not.toContainEqual(expect.objectContaining({ task_type: 'water' }));
});

test('repots with a previously unknown pot or an unknown replacement remain undoable after rollback', async () => {
  const unknown = { pot_type: null, pot_size_cm: null, soil_type: null };
  const known = { pot_type: 'terracotta', pot_size_cm: 20, soil_type: 'cactus' };
  const defaults = { pot_type: 'plastic', pot_size_cm: null, soil_type: 'all_purpose' };
  const cases = [];
  for (const [previous, next, restored] of [[unknown, known, defaults], [known, unknown, known]]) {
    const created = await add(previous);
    expect(created.status).toBe(201);
    const { id } = await created.json();
    const path = `/api/plants/${id}`;
    const request_id = randomUUID();
    const repotted = await request(path + '/repot', 'POST', { ...next, request_id });
    expect(repotted.status).toBe(200);
    const { event } = await repotted.json();
    cases.push({ path, eventId: event.id, request_id, restored });
  }
  const [up, down] = readFileSync('migrations/20261120100000_nullable_plant_attributes.sql', 'utf8').split('-- migrate:down');
  await database.query(down);
  try {
    const { path, request_id, eventId } = cases[1];
    const replay = await request(path + '/repot', 'POST', { ...defaults, request_id });
    expect.soft(replay.status, 'the old default payload still identifies the same repot').toBe(200);
    if (replay.ok) expect((await replay.json()).event.id).toBe(eventId);
    for (const { path, eventId, restored } of cases) {
      expect.soft((await undo(eventId)).status, `undo repot to ${restored.pot_type}`).toBe(200);
      expect.soft(await body(path)).toMatchObject(restored);
    }
  } finally {
    await database.query(up);
  }
});

test('rolling back nullable attributes restores their original defaults without replacing known values', async () => {
  const { search_id } = await search();
  const response = await add({ ...unset, search_id });
  expect(response.status).toBe(201);
  const { id } = await response.json();
  const [up, down] = readFileSync('migrations/20261120100000_nullable_plant_attributes.sql', 'utf8').split('-- migrate:down');
  const client = await database.connect();
  try {
    await client.query('BEGIN');
    await client.query("UPDATE plants SET soil_type = 'cactus' WHERE id = $1", [id]);
    await client.query(down);
    const row = await client.query('SELECT pot_type, soil_type, plant_size, growth_stage, fertilizer_type, drainage FROM plants WHERE id = $1', [id]);
    expect(row.rows[0]).toEqual({ pot_type: 'plastic', soil_type: 'cactus', plant_size: 'small', growth_stage: 'young', fertilizer_type: 'liquid', drainage: false });
    const columns = () => client.query(`SELECT is_nullable, column_default FROM information_schema.columns
      WHERE table_name = 'plants' AND column_name = ANY($1)`, [Object.keys(unset)]);
    expect((await columns()).rows).toEqual(Array.from({ length: 6 }, () => ({ is_nullable: 'NO', column_default: expect.any(String) })));
    await client.query(up);
    expect((await columns()).rows).toEqual(Array.from({ length: 6 }, () => ({ is_nullable: 'YES', column_default: null })));
  } finally {
    await client.query('ROLLBACK');
    client.release();
  }
});

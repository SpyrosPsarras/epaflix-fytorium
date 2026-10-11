import { error, json } from '@sveltejs/kit';
import type pg from 'pg';
import { randomUUID } from 'node:crypto';
import { taskRules } from '$lib/schedule/rules';
import { taskViewSettings } from './task-view-settings';
import { schedule, taskTypes, type TaskType, type Task, type ScheduleInput } from '$lib/schedule/engine';
import type { CareEvent, ListedTask, TaskList } from '$lib/tasks';
import type { Identity } from './auth';
import { getPlant, isUuid, plantSelect, potErrors } from './plants';
import { db } from './db';
import { config } from './config';
import { now } from './clock';
import { effectiveCareProfiles } from './care/catalogue';
type Database = pg.Pool | pg.PoolClient;

// Intl owns time-zone and DST conversion; the engine receives calendar dates only.
export function calendarDate(at: Date | string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: config().householdTimezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(at));
}

export async function history(plantId: string, household: string): Promise<CareEvent[]> {
  await getPlant(plantId, household);
  const result = await db().query('SELECT * FROM care_events WHERE plant_id = $1 AND household_id = $2 ORDER BY occurred_at DESC, event_order DESC', [plantId, household]);
  return result.rows.map(event => ({ ...event, occurred_on: calendarDate(event.occurred_at) }));
}

async function schedulePlantTasks(plants: Awaited<ReturnType<typeof getPlant>>[], household: string, today: string, database: Database = db()) {
  const plantIds = plants.map(plant => plant.id);
  const [sites, events, intervals, settings, profiles] = await Promise.all([
    database.query('SELECT * FROM sites WHERE id = ANY($1::uuid[]) AND household_id = $2', [plants.map(plant => plant.site_id), household]),
    database.query('SELECT * FROM care_events WHERE plant_id = ANY($1::uuid[]) AND household_id = $2 AND undone_at IS NULL ORDER BY occurred_at DESC, event_order DESC', [plantIds, household]),
    database.query('SELECT plant_id, task_type, interval_days FROM plant_task_intervals WHERE plant_id = ANY($1::uuid[]) AND household_id = $2', [plantIds, household]),
    database.query('SELECT misting_enabled FROM households WHERE id = $1', [household]),
    effectiveCareProfiles(plants.map(plant => plant.gbif_key), household),
  ]);
  const sitesById = new Map(sites.rows.map(site => [site.id, site]));
  const historyByPlant: Record<string, ScheduleInput['history']> = {};
  const seen = new Set<string>();
  for (const event of events.rows) {
    const key = event.plant_id + ':' + event.task_type;
    const history = (historyByPlant[event.plant_id] ??= {})[event.task_type as TaskType] ??= {};
    if (event.kind === 'completed') {
      history.lastCompletedOn ??= calendarDate(event.occurred_at);
      seen.add(key);
    } else if (!seen.has(key)) {
      const field = event.kind === 'snoozed' ? 'snoozedUntil' : 'skippedOn';
      history[field] = calendarDate(event.occurred_at);
      seen.add(key);
    }
  }
  const intervalsByPlant: Record<string, ScheduleInput['customIntervals']> = {};
  for (const interval of intervals.rows) {
    (intervalsByPlant[interval.plant_id] ??= {})[interval.task_type as TaskType] = interval.interval_days;
  }
  return new Map(plants.map(plant => [plant.id, schedule({
    careProfile: profiles.get(plant.gbif_key)!, site: sitesById.get(plant.site_id), pot: plant, plant, household: settings.rows[0],
    history: historyByPlant[plant.id] ?? {}, customIntervals: intervalsByPlant[plant.id] ?? {},
    addedOn: calendarDate(plant.created_at), today,
  })]));
}

export async function plantTasks(plantId: string, household: string): Promise<Partial<Record<TaskType, Task>>> {
  const plant = await getPlant(plantId, household);
  if (plant.status === 'graveyard') return {};
  return (await schedulePlantTasks([plant], household, calendarDate(now()))).get(plant.id)!;
}

export async function listedTasks(identity: Identity, view: TaskList['view']): Promise<TaskList> {
  const household = identity.household.id;
  const today = calendarDate(now());
  const settings = await taskViewSettings(identity);
  const end = new Date(today + 'T00:00:00Z');
  end.setUTCDate(end.getUTCDate() + (view === 'upcoming' ? 13 : 0));
  const through = end.toISOString().slice(0, 10);
  const plants = await db().query(plantSelect + " WHERE p.household_id = $1 AND p.status = 'active' AND ($2 = 'all' OR t.location = $2) ORDER BY display_name, p.id", [household, settings.filter]);
  const groups = Object.fromEntries(taskTypes.map(type => [type, new Array<ListedTask>()])) as TaskList['groups'];
  const schedules = await schedulePlantTasks(plants.rows, household, today);
  const completedByTask = new Map<string, CareEvent>();
  if (settings.show_completed) {
    const events = await db().query("SELECT * FROM care_events WHERE plant_id = ANY($1::uuid[]) AND household_id = $2 AND kind = 'completed' AND undone_at IS NULL ORDER BY occurred_at DESC, event_order DESC", [plants.rows.map(plant => plant.id), household]);
    for (const event of events.rows) {
      const key = event.plant_id + ':' + event.task_type;
      if (calendarDate(event.occurred_at) === today && !completedByTask.has(key)) completedByTask.set(key, { ...event, occurred_on: today });
    }
  }
  for (const plant of plants.rows) {
    for (const taskType of taskTypes) {
      const task = schedules.get(plant.id)![taskType];
      const completed = completedByTask.get(plant.id + ':' + taskType);
      if ((task.status === 'due' && task.dueOn <= through) || (completed && (view === 'today' || task.status === 'due'))) {
        groups[taskType].push({ ...task, plant_id: plant.id, plant_name: plant.display_name, site_name: plant.site_name, task_type: taskType, ...(completed ? { completed_event: completed } : {}) });
      }
    }
  }
  const compare = (a: ListedTask, b: ListedTask) => {
    const names = settings.sort === 'plant' ? a.plant_name.localeCompare(b.plant_name) : settings.sort === 'site' ? a.site_name.localeCompare(b.site_name) : taskTypes.indexOf(a.task_type) - taskTypes.indexOf(b.task_type);
    return names || (a.status === 'due' && b.status === 'due' ? a.dueOn.localeCompare(b.dueOn) : 0) || a.plant_name.localeCompare(b.plant_name) || a.plant_id.localeCompare(b.plant_id) || taskTypes.indexOf(a.task_type) - taskTypes.indexOf(b.task_type);
  };
  const rows = Object.values(groups).flat().sort(compare);
  for (const tasks of Object.values(groups)) tasks.sort(compare);
  const sections = new Map<string, TaskList['sections'][number]>();
  for (const row of rows) {
    const key = view === 'upcoming' ? row.completed_event || (row.status === 'due' && row.dueOn <= today) ? today : row.status === 'due' ? row.dueOn : today
      : settings.sort === 'task' ? row.task_type : settings.sort === 'plant' ? row.plant_id : row.site_name;
    const label = view === 'upcoming' ? key : settings.sort === 'task' ? taskRules[row.task_type].label : settings.sort === 'plant' ? row.plant_name : row.site_name;
    if (!sections.has(key)) sections.set(key, { key, label, tasks: [] });
    sections.get(key)!.tasks.push(row);
  }
  if (view === 'today' && settings.sort === 'task') {
    for (const type of taskTypes) if (!sections.has(type)) sections.set(type, { key: type, label: taskRules[type].label, tasks: [] });
  }
  const ordered = [...sections.values()];
  if (view === 'upcoming') ordered.sort((a, b) => a.key.localeCompare(b.key));
  else if (settings.sort === 'task') ordered.sort((a, b) => taskTypes.indexOf(a.key as TaskType) - taskTypes.indexOf(b.key as TaskType));
  return { today, view, settings, groups, sections: ordered };
}

export async function undo(identity: Identity, eventId: string) {
  if (!isUuid(eventId)) error(404, 'Care event not found');
  const household = identity.household.id;
  const client = await db().connect();
  let event;
  try {
    await client.query('BEGIN');
    const existing = await client.query('SELECT * FROM care_events WHERE id = $1 AND household_id = $2', [eventId, household]);
    if (!existing.rowCount) error(404, 'Care event not found');
    const plant = await client.query('SELECT status FROM plants WHERE id = $1 AND household_id = $2 FOR UPDATE', [existing.rows[0].plant_id, household]);
    if (!plant.rowCount) error(404, 'Plant not found');
    if (plant.rows[0].status !== 'active') error(409, 'Graveyard plants have no tasks. Restore the plant first.');
    const result = await client.query('UPDATE care_events SET undone_at = $1, undone_by = $2 WHERE (id = $3 OR ($4::uuid IS NOT NULL AND batch_id = $4)) AND household_id = $5 AND undone_at IS NULL RETURNING *', [now(), identity.member.id, eventId, existing.rows[0].batch_id, household]);
    event = result.rows.find(row => row.id === eventId);
    if (!event) error(409, 'Care event already undone');
    if (event.previous_pot) {
      const later = await client.query('SELECT id FROM care_events WHERE plant_id = $1 AND household_id = $2 AND previous_pot IS NOT NULL AND undone_at IS NULL AND event_order > $3', [event.plant_id, household, event.event_order]);
      if (later.rowCount) error(409, 'Undo the later repot first.');
      const pot = event.previous_pot;
      await client.query('UPDATE plants SET pot_type = $1, pot_size_cm = $2, soil_type = $3 WHERE id = $4 AND household_id = $5', [pot.pot_type, pot.pot_size_cm, pot.soil_type, event.plant_id, household]);
    }
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
  return { event: { ...event, occurred_on: calendarDate(event.occurred_at) }, task: (await plantTasks(event.plant_id, household))[event.task_type as TaskType] };
}

// A plant lock serializes care and undo. The request record also survives an empty batch.
async function careAction(identity: Identity, plantId: string, requestId: string, action: string, payload: Record<string, unknown>,
  write: (client: pg.PoolClient, plant: Awaited<ReturnType<typeof getPlant>>, at: Date) => Promise<void>) {
  const household = identity.household.id;
  await getPlant(plantId, household);
  if (!isUuid(requestId)) error(422, 'Send a UUID request_id.');
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    const locked = await client.query(plantSelect + ' WHERE p.id = $1 AND p.household_id = $2 FOR UPDATE OF p', [plantId, household]);
    if (!locked.rowCount) error(404, 'Plant not found');
    if (locked.rows[0].status !== 'active') error(409, 'Graveyard plants have no tasks. Restore the plant first.');
    const existingEvent = await client.query('SELECT request_id FROM care_events WHERE request_id = $1', [requestId]);
    const existingAction = await client.query('SELECT request_id FROM care_actions WHERE request_id = $1', [requestId]);
    if (existingEvent.rowCount && !existingAction.rowCount) error(409, 'request_id already used for another action.');
    const claimed = await client.query('INSERT INTO care_actions (request_id, household_id, plant_id, by_member, action, payload) VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT DO NOTHING RETURNING request_id', [requestId, household, plantId, identity.member.id, action, payload]);
    const previous = await client.query('SELECT * FROM care_actions WHERE request_id = $1 AND household_id = $2 AND plant_id = $3 AND by_member = $4 AND action = $5 AND payload = $6::jsonb', [requestId, household, plantId, identity.member.id, action, payload]);
    if (!previous.rowCount) error(409, 'request_id already used for another action.');
    if (claimed.rowCount) await write(client, locked.rows[0], now());
    const events = await client.query('SELECT * FROM care_events WHERE (request_id = $1 OR batch_id = $1) AND household_id = $2 ORDER BY event_order', [requestId, household]);
    await client.query('COMMIT');
    return events.rows.map(event => ({ ...event, occurred_on: calendarDate(event.occurred_at) }));
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
}

async function recordEvent(client: pg.PoolClient, identity: Identity, plantId: string, taskType: TaskType, kind: CareEvent['kind'], requestId: string, at: Date, batchId: string | null = null, previousPot: Record<string, unknown> | null = null) {
  const member = await client.query('SELECT display_name FROM members WHERE id = $1 AND household_id = $2', [identity.member.id, identity.household.id]);
  await client.query('INSERT INTO care_events (household_id, plant_id, task_type, kind, occurred_at, by_member, by_name, request_id, batch_id, previous_pot) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)',
    [identity.household.id, plantId, taskType, kind, at, identity.member.id, member.rows[0].display_name, requestId, batchId, previousPot]);
}

export async function completeAll(identity: Identity, plantId: string, requestId: string) {
  const events = await careAction(identity, plantId, requestId, 'complete-all', {}, async (client, plant, at) => {
    const today = calendarDate(at);
    const tasks = (await schedulePlantTasks([plant], identity.household.id, today, client)).get(plantId)!;
    for (const type of taskTypes) {
      const task = tasks[type];
      if (task.status === 'due' && task.dueOn <= today) await recordEvent(client, identity, plantId, type, 'completed', randomUUID(), at, requestId);
    }
  });
  return { events, tasks: await plantTasks(plantId, identity.household.id) };
}

export async function repot(identity: Identity, plantId: string, input: Record<string, unknown>) {
  await getPlant(plantId, identity.household.id);
  const errors = potErrors(input);
  if (Object.keys(errors).length) return json({ errors }, { status: 422 });
  if (typeof input.request_id !== 'string') error(422, 'Send a UUID request_id.');
  const pot = { pot_type: input.pot_type, pot_size_cm: input.pot_size_cm ?? null, soil_type: input.soil_type };
  const requestId = input.request_id;
  const events = await careAction(identity, plantId, requestId, 'repot', pot, async (client, plant, at) => {
    const previous = { pot_type: plant.pot_type, pot_size_cm: plant.pot_size_cm, soil_type: plant.soil_type };
    await client.query('UPDATE plants SET pot_type = $1, pot_size_cm = $2, soil_type = $3 WHERE id = $4 AND household_id = $5', [pot.pot_type, pot.pot_size_cm, pot.soil_type, plantId, identity.household.id]);
    await recordEvent(client, identity, plantId, 'repot', 'completed', requestId, at, null, previous);
  });
  return json({ event: events[0], task: (await plantTasks(plantId, identity.household.id)).repot });
}

export async function readTaskInput(request: Request): Promise<Record<string, unknown>> {
  let input;
  try {
    if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') throw new Error('Expected JSON');
    input = await request.json();
  } catch { error(400, 'Send a valid JSON object.'); }
  if (!input || typeof input !== 'object' || Array.isArray(input)) error(400, 'Send a valid JSON object.');
  return input;
}

export async function complete(identity: Identity, plantId: string, taskType: TaskType, requestId: string, kind: CareEvent['kind'] = 'completed') {
  if (!taskTypes.includes(taskType)) error(404, 'Task type not found');
  const events = await careAction(identity, plantId, requestId, taskType + ':' + kind, {}, async (client, plant, at) => {
    const task = (await schedulePlantTasks([plant], identity.household.id, calendarDate(at), client)).get(plantId)![taskType];
    if (task.status === 'not_applicable') error(422, 'This task is not applicable.');
    await recordEvent(client, identity, plantId, taskType, kind, requestId, at);
  });
  return { event: events[0], task: (await plantTasks(plantId, identity.household.id))[taskType] };
}

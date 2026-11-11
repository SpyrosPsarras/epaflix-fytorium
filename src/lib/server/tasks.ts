import { error, json } from '@sveltejs/kit';
import type pg from 'pg';
import { randomUUID } from 'node:crypto';
import { taskRules } from '$lib/schedule/rules';
import { taskViewSettings } from './task-view-settings';
import { getCustomTask } from './custom-tasks';
import { schedule, postponedUntil, taskTypes, type TaskType, type Task, type ScheduleInput } from '$lib/schedule/engine';
import { taskKey, taskPresentation, type CareEvent, type ListedTask, type TaskList, type TaskViewSettings, type PlantSchedule, type ScheduledTask } from '$lib/tasks';
import type { Identity } from './auth';
import { visiblePlantSql, requireSharedPlant, requireOwnUndo } from './policy';
import { requireUndo, withUndoFlag, withUndoFlags } from './undo-policy';
import { getPlant, isUuid, plantSelectFor, potErrors } from './plants';
import { db, withSnapshot, withTransaction } from './db';
import { now, calendarDate } from './clock';
import type { ForecastDay } from '$lib/weather';
import { recentRain } from './weather/rain';
import { taskWeather, weatherReport } from './weather/cache';
import { effectiveCareProfiles } from './care/catalogue';
type Database = pg.Pool | pg.PoolClient;

function taskIn(schedule: Partial<PlantSchedule>, key: string) {
  return isUuid(key) ? schedule.customTasks?.[key] : schedule[key as TaskType];
}
const dueOf = (task: Task) => task.status === 'due' ? task.dueOn : null;
// Deleted custom tasks keep their row, so history and today's completions keep their names.
const namedEvents = 'SELECT e.*, e.due_on::text, e.postponed_until::text, c.name AS custom_task_name FROM care_events e LEFT JOIN custom_tasks c ON c.id = e.custom_task_id AND c.household_id = e.household_id';

export async function history(plantId: string, identity: Identity): Promise<CareEvent[]> {
  const household = identity.household.id;
  await getPlant(plantId, household, identity.language);
  const result = await db().query(namedEvents + ' WHERE e.plant_id = $1 AND e.household_id = $2 ORDER BY e.occurred_at DESC, e.event_order DESC', [plantId, household]);
  return withUndoFlags(identity, result.rows.map(event => ({ ...event, occurred_on: calendarDate(event.occurred_at) })));
}

async function schedulePlantTasks(plants: Awaited<ReturnType<typeof getPlant>>[], household: string, today: string, language: Identity['language'], database: Database = db(), taskOnly = false) {
  const plantIds = plants.map(plant => plant.id);
  const [sites, events, intervals, profiles, custom, weather] = await Promise.all([
    database.query('SELECT * FROM sites WHERE id = ANY($1::uuid[]) AND household_id = $2', [plants.map(plant => plant.site_id), household]),
    database.query(namedEvents + ' WHERE e.plant_id = ANY($1::uuid[]) AND e.household_id = $2 AND e.undone_at IS NULL ORDER BY e.occurred_at DESC, e.event_order DESC', [plantIds, household]),
    database.query('SELECT plant_id, task_type, interval_days FROM plant_task_intervals WHERE plant_id = ANY($1::uuid[]) AND household_id = $2', [plantIds, household]),
    effectiveCareProfiles(plants.map(plant => plant.gbif_key), household, database),
    database.query('SELECT * FROM custom_tasks WHERE household_id = $1 AND deleted_at IS NULL AND (plant_id = ANY($2::uuid[]) OR site_id = ANY($3::uuid[])) ORDER BY name, id', [household, plantIds, plants.map(plant => plant.site_id)]),
    taskOnly ? taskWeather(household, database) : weatherReport(household, language),
  ]);
  const sitesById = new Map(sites.rows.map(site => [site.id, site]));
  const historyByPlant: Record<string, ScheduleInput['history']> = {};
  const seen = new Set<string>();
  for (const event of events.rows) {
    const identity = taskKey(event);
    const key = event.plant_id + ':' + identity;
    const history = (historyByPlant[event.plant_id] ??= {})[identity] ??= {};
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
  const schedules = new Map(plants.map(plant => {
    const applicable = custom.rows.filter(task => task.plant_id === plant.id || task.site_id === plant.site_id);
    const histories = historyByPlant[plant.id] ?? {};
    const result = schedule({
      careProfile: profiles.get(plant.gbif_key)!, site: sitesById.get(plant.site_id), pot: plant, plant,
      history: histories, customIntervals: intervalsByPlant[plant.id] ?? {},
      customTasks: applicable.map(task => ({ id: task.id, intervalDays: task.interval_days, createdOn: calendarDate(task.created_at) })),
      addedOn: calendarDate(plant.created_at), today,
      weather: sitesById.get(plant.site_id)?.location === 'outdoor' ? weather.snapshot ?? undefined : undefined,
    });
    const withPostponement = (key: string, task: Task) => {
      const history = histories[key];
      return { ...task, postponed: task.status !== 'due' ? null : history?.snoozedUntil ? 'snoozed' as const : history?.skippedOn ? 'skipped' as const : null };
    };
    const tasks = Object.fromEntries(taskTypes.map(type => [type, withPostponement(type, result[type])]));
    const customTasks = Object.fromEntries(applicable.map(task => [task.id, { ...withPostponement(task.id, result.customTasks[task.id]), name: task.name, custom_task_id: task.id }]));
    return [plant.id, { ...tasks, customTasks } as PlantSchedule];
  }));
  return { schedules, sites: sitesById, profiles, weather, events: events.rows };
}

export async function plantTasks(plantId: string, household: string, language: Identity['language']): Promise<Partial<PlantSchedule>> {
  const plant = await getPlant(plantId, household, language);
  if (plant.status === 'graveyard') return {};
  return (await schedulePlantTasks([plant], household, calendarDate(now()), language)).schedules.get(plant.id)!;
}

export async function listedTasks(identity: Identity, view: TaskList['view']): Promise<TaskList> {
  return (await listedTaskContext(identity, { view })).tasks;
}
// An undo returns the member's lists, so a device shows every row it reversed in its earlier state without rebuilding them.
async function undoneLists(identity: Identity) {
  const [today, upcoming] = await Promise.all([listedTasks(identity, 'today'), listedTasks(identity, 'upcoming')]);
  return { today, upcoming };
}

// A returning sitter may have saved a site view or Show others' completed tasks while they were family. Sitters see neither.
async function memberSettings(identity: Identity, database?: pg.PoolClient): Promise<TaskViewSettings> {
  const saved = await taskViewSettings(identity, database);
  return identity.member.role === 'sitter' ? { ...saved, sort: 'task', filter: 'all', show_completed: false } : saved;
}
// Your own events of today always, so a setting cannot take away their Undo; others' only with Show others' completed tasks.
const shownToday = (identity: Identity, settings: TaskViewSettings, today: string) => (event: { occurred_at: Date; by_member: string | null }) =>
  calendarDate(event.occurred_at) === today && (settings.show_completed || event.by_member === identity.member.id);
// A completion shown today stands for its occurrence, so the next one is listed only once a later snooze or skip postponed it.
const pendingDue = (task: ScheduledTask | undefined, completedToday: boolean) => task?.status === 'due' && (!completedToday || task.postponed) ? task.dueOn : null;

// Read inside an Undo's snapshot: the due date of each plant task's pending occurrence in the member's listing, whatever the view's dates.
async function pendingOccurrences(client: pg.PoolClient, identity: Identity, plantIds: string[]) {
  const today = calendarDate(now());
  const plants = await client.query(plantSelectFor(identity.language) + ' WHERE p.id = ANY($1::uuid[]) AND p.household_id = $2', [plantIds, identity.household.id]);
  const { schedules, events } = await schedulePlantTasks(plants.rows, identity.household.id, today, identity.language, client, true);
  const shown = shownToday(identity, await memberSettings(identity, client), today);
  const completed = new Set(events.filter(event => event.kind === 'completed' && shown(event)).map(event => event.plant_id + ':' + taskKey(event)));
  return (event: Pick<CareEvent, 'plant_id' | 'task_type' | 'custom_task_id'>) =>
    pendingDue(taskIn(schedules.get(event.plant_id) ?? {}, taskKey(event)), completed.has(event.plant_id + ':' + taskKey(event)));
}
// An Undo brought back the occurrence its event's task has pending after it, unless that one was already pending before it.
function restoredBy(before: (event: Pick<CareEvent, 'plant_id' | 'task_type' | 'custom_task_id'>) => string | null, after: typeof before,
  events: Pick<CareEvent, 'id' | 'plant_id' | 'task_type' | 'custom_task_id'>[]) {
  return events.map(event => {
    const due = after(event);
    return { event_id: event.id, occurrence: due && due !== before(event)
      ? { plant_id: event.plant_id, task_type: event.task_type, custom_task_id: event.custom_task_id ?? null, due_on: due } : null };
  });
}

// The feed reuses this batch, ignoring Today filters and completed-row preferences.
export async function listedTaskContext(identity: Identity, { view, forFeed = false, batch }: {
  view: TaskList['view']; forFeed?: boolean;
  batch?: Awaited<ReturnType<typeof schedulePlantTasks>> & { plants: Awaited<ReturnType<typeof getPlant>>[] };
}) {
  const household = identity.household.id;
  const today = calendarDate(now());
  const settings: TaskViewSettings = forFeed ? { sort: 'task', filter: 'all', show_completed: false } : await memberSettings(identity);
  const end = new Date(today + 'T00:00:00Z');
  end.setUTCDate(end.getUTCDate() + (view === 'upcoming' ? 13 : 0));
  const through = end.toISOString().slice(0, 10);
  const plants = batch ? { rows: batch.plants.filter(plant => settings.filter === 'all' || batch.sites.get(plant.site_id)?.location === settings.filter) }
    : await db().query(plantSelectFor(identity.language) + ` WHERE p.household_id = $1 AND ${visiblePlantSql('$3')} AND ($2 = 'all' OR t.location = $2) ORDER BY display_name, p.id`, [household, settings.filter, identity.member.id]);
  const groups = Object.fromEntries(taskTypes.map(type => [type, new Array<ListedTask>()])) as TaskList['groups'];
  const context = batch ?? await schedulePlantTasks(plants.rows, household, today, identity.language, db(), identity.member.role === 'sitter');
  const { schedules } = context;
  const entries = (schedule: PlantSchedule) => [
    ...taskTypes.map(type => ({ ...schedule[type], task_type: type, custom_task_id: null, custom_task_name: null })),
    ...Object.values(schedule.customTasks).map(task => ({ ...task, task_type: null, custom_task_name: task.name })),
  ];
  const listed = new Map(plants.rows.map(plant => [plant.id, plant]));
  const rowFor = (plant: typeof plants.rows[number], task: ScheduledTask & Pick<ListedTask, 'task_type' | 'custom_task_id' | 'custom_task_name'>): ListedTask =>
    ({ ...task, plant_id: plant.id, plant_name: plant.display_name, site_name: plant.site_name, cover_photo_id: plant.cover_photo_id ?? null });
  // From the events, so a schedule change cannot hide a completion, snooze or skip.
  const shown = shownToday(identity, settings, today);
  const todays = forFeed ? [] : await withUndoFlags(identity, context.events
    .filter(event => listed.has(event.plant_id) && shown(event))
    .reverse().map(event => ({ ...event, occurred_on: today })));
  const completed = new Set<string>();
  for (const event of todays) {
    const key = taskKey(event);
    const plant = listed.get(event.plant_id)!;
    const task = entries(schedules.get(plant.id)!).find(task => taskKey(task) === key)
      ?? { status: 'not_applicable' as const, basedOn: [], postponed: null, task_type: event.task_type, custom_task_id: event.custom_task_id ?? null, custom_task_name: event.custom_task_name ?? null };
    const row: ListedTask = { ...rowFor(plant, task), postponed: null, completed_event: event };
    // Events from before the date was stored use today's interval.
    if (event.kind !== 'completed') row.postponed_until = event.postponed_until ?? (task.status === 'due' ? postponedUntil(event.kind, today, task.intervalDays) : null);
    (groups[key] ??= []).push(row);
    if (event.kind === 'completed') completed.add(plant.id + ':' + key);
  }
  for (const plant of plants.rows) {
    for (const task of entries(schedules.get(plant.id)!)) {
      const key = taskKey(task);
      const due = pendingDue(task, completed.has(plant.id + ':' + key));
      if (due && due <= through) (groups[key] ??= []).push(rowFor(plant, task));
    }
  }
  // A completion sits at the due date it completed, so it never moves later in the day. Events from before that date was stored use their own day.
  const dueOn = (row: ListedTask) => row.completed_event ? row.completed_event.due_on ?? row.completed_event.occurred_on : row.status === 'due' ? row.dueOn : undefined;
  const taskOrder = (task: ListedTask) => task.task_type ? taskTypes.indexOf(task.task_type) : taskTypes.length;
  const compareTypes = (a: ListedTask, b: ListedTask) => taskOrder(a) - taskOrder(b) || taskPresentation(a).label.localeCompare(taskPresentation(b).label) || taskKey(a).localeCompare(taskKey(b));
  const compare = (a: ListedTask, b: ListedTask) => {
    const names = settings.sort === 'plant' ? a.plant_name.localeCompare(b.plant_name) : settings.sort === 'site' ? a.site_name.localeCompare(b.site_name) : compareTypes(a, b);
    const [first, second] = [dueOn(a), dueOn(b)];
    return names || (first && second ? first.localeCompare(second) : 0) || a.plant_name.localeCompare(b.plant_name) || a.plant_id.localeCompare(b.plant_id) || compareTypes(a, b);
  };
  const rows = Object.values(groups).flat().sort(compare);
  for (const tasks of Object.values(groups)) tasks.sort(compare);
  const sections = new Map<string, TaskList['sections'][number]>();
  for (const row of rows) {
    const due = dueOn(row);
    const day = view === 'upcoming' ? due && due > today ? due : today : null;
    const group = settings.sort === 'task' ? taskKey(row) : settings.sort === 'plant' ? row.plant_id : row.site_name;
    const key = day ? day + ':' + group : group;
    const label = settings.sort === 'task' ? taskPresentation(row).label : settings.sort === 'plant' ? row.plant_name : row.site_name;
    if (!sections.has(key)) sections.set(key, { key, label, ...(day ? { day } : {}), tasks: [] });
    sections.get(key)!.tasks.push(row);
  }
  if (view === 'today' && settings.sort === 'task') {
    for (const type of taskTypes) if (!sections.has(type)) sections.set(type, { key: type, label: taskRules[type].label, tasks: [] });
  }
  const ordered = [...sections.values()];
  if (view === 'upcoming') ordered.sort((a, b) => a.day!.localeCompare(b.day!));
  else if (settings.sort === 'task') {
    const order = (key: string) => taskTypes.includes(key as TaskType) ? taskTypes.indexOf(key as TaskType) : taskTypes.length;
    ordered.sort((a, b) => order(a.key) - order(b.key) || a.label.localeCompare(b.label) || a.key.localeCompare(b.key));
  }
  return { ...context, tasks: { today, view, settings, groups, sections: ordered } satisfies TaskList, plants: plants.rows };
}

export async function undo(identity: Identity, eventId: string) {
  await requireOwnUndo(identity, eventId);
  if (!isUuid(eventId)) error(404, 'Care event not found');
  const household = identity.household.id;
  const { event, restored } = await withSnapshot(async (client) => {
    const existing = await client.query('SELECT * FROM care_events WHERE id = $1 AND household_id = $2', [eventId, household]);
    if (!existing.rowCount) error(404, 'Care event not found');
    const plant = await client.query('SELECT status, pot_type, pot_size_cm, soil_type FROM plants WHERE id = $1 AND household_id = $2 FOR UPDATE', [existing.rows[0].plant_id, household]);
    if (!plant.rowCount) error(404, 'Plant not found');
    await requireUndo(identity, eventId, false, client);
    const before = await pendingOccurrences(client, identity, [existing.rows[0].plant_id]);
    const result = await client.query('UPDATE care_events SET undone_at = $1, undone_by = $2 WHERE (id = $3 OR linked_event_id = $3) AND household_id = $4 AND undone_at IS NULL RETURNING *, due_on::text, postponed_until::text', [now(), identity.member.id, eventId, household]);
    const event = result.rows.find(row => row.id === eventId);
    if (!event) error(409, 'Care event already undone');
    if (event.previous_pot) {
      const pot = event.previous_pot;
      await client.query('UPDATE plants SET pot_type = $1, pot_size_cm = $2, soil_type = $3 WHERE id = $4 AND household_id = $5', [pot.pot_type, pot.pot_size_cm, pot.soil_type, event.plant_id, household]);
    }
    return { event, restored: restoredBy(before, await pendingOccurrences(client, identity, [event.plant_id]), result.rows) };
  });
  const tasks = await plantTasks(event.plant_id, household, identity.language);
  return { event: await withUndoFlag(identity, { ...event, occurred_on: calendarDate(event.occurred_at) }), task: taskIn(tasks, taskKey(event)), restored, ...await undoneLists(identity) };
}

export async function dueDates(client: pg.PoolClient, identity: Identity, plantId: string) {
  const plant = await client.query(plantSelectFor(identity.language) + ' WHERE p.id = $1 AND p.household_id = $2', [plantId, identity.household.id]);
  const tasks = (await schedulePlantTasks(plant.rows, identity.household.id, calendarDate(now()), identity.language, client, true)).schedules.get(plantId)!;
  return Object.fromEntries(taskTypes.map(type => [type, dueOf(tasks[type])])) as Record<TaskType, string | null>;
}

// Care commands take the household lock before plant locks.
// The request record survives empty batches and remembers taps that reused another rain batch.
async function careAction(identity: Identity, plantId: string | null, requestId: string, action: string, payload: Record<string, unknown>,
  write: (client: pg.PoolClient, plants: Awaited<ReturnType<typeof getPlant>>[], at: Date) => Promise<void>) {
  const household = identity.household.id;
  if (plantId) {
    await requireSharedPlant(identity, plantId);
    await getPlant(plantId, household, identity.language);
  }
  if (!isUuid(requestId)) error(422, 'Send a UUID request_id.');
  return await withTransaction(async (client) => {
    // NO KEY UPDATE still permits lifecycle commands' household foreign-key checks.
    await client.query('SELECT id FROM households WHERE id = $1 FOR NO KEY UPDATE', [household]);
    let plants: Awaited<ReturnType<typeof getPlant>>[] = [];
    if (plantId) {
      const locked = await client.query(plantSelectFor(identity.language) + ' WHERE p.id = $1 AND p.household_id = $2 FOR UPDATE OF p', [plantId, household]);
      if (!locked.rowCount) error(404, 'Plant not found');
      if (locked.rows[0].status !== 'active') error(409, 'Graveyard plants have no tasks. Restore the plant first.');
      plants = locked.rows;
    }
    const existingEvent = await client.query('SELECT request_id FROM care_events WHERE request_id = $1 AND household_id = $2', [requestId, household]);
    const existingAction = await client.query('SELECT request_id FROM care_actions WHERE request_id = $1 AND household_id = $2', [requestId, household]);
    if (existingEvent.rowCount && !existingAction.rowCount) error(409, 'request_id already used for another action.');
    const claimed = await client.query('INSERT INTO care_actions (request_id, household_id, plant_id, by_member, action, payload, created_at) VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT DO NOTHING RETURNING request_id', [requestId, household, plantId, identity.member.id, action, payload, now()]);
    const previous = await client.query('SELECT request_id FROM care_actions WHERE request_id = $1 AND household_id = $2 AND plant_id IS NOT DISTINCT FROM $3::uuid AND by_member = $4 AND action = $5 AND payload = $6::jsonb', [requestId, household, plantId, identity.member.id, action, payload]);
    if (!previous.rowCount) error(409, 'request_id already used for another action.');
    if (claimed.rowCount) await write(client, plants, now());
    const batch = (await client.query('SELECT b.request_id, b.undone_at FROM care_actions a JOIN care_actions b ON b.request_id = COALESCE(a.batch_id, a.request_id) WHERE a.request_id = $1 AND a.household_id = $2', [requestId, household])).rows[0];
    const events = await client.query('SELECT *, due_on::text, postponed_until::text FROM care_events WHERE (request_id = $1 OR batch_id = $1 OR linked_event_id IN (SELECT id FROM care_events WHERE request_id = $1 AND household_id = $2)) AND household_id = $2 ORDER BY event_order', [batch.request_id, household]);
    return { action: batch, events: await withUndoFlags(identity, events.rows.map(event => ({ ...event, occurred_on: calendarDate(event.occurred_at) })), client) };
  });
}

// dueOn is the acted-on task's due date at that moment, so the completed row keeps its place whatever the schedule does later.
async function recordEvent(client: pg.PoolClient, identity: Identity, plantId: string, taskType: string, kind: CareEvent['kind'], requestId: string, at: Date, dueOn: string | null, { batchId = null, previousPot = null, linkedEventId = null, until = null }: { batchId?: string | null; previousPot?: Record<string, unknown> | null; linkedEventId?: string | null; until?: string | null } = {}) {
  const member = await client.query('SELECT display_name FROM members WHERE id = $1 AND household_id = $2', [identity.member.id, identity.household.id]);
  const event = await client.query('INSERT INTO care_events (household_id, plant_id, task_type, kind, occurred_at, by_member, by_name, request_id, batch_id, previous_pot, custom_task_id, linked_event_id, due_on, postponed_until) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14) RETURNING id',
    [identity.household.id, plantId, taskTypes.includes(taskType as TaskType) ? taskType : null, kind, at, identity.member.id, member.rows[0].display_name, requestId, batchId, previousPot, isUuid(taskType) ? taskType : null, linkedEventId, dueOn, until]);
  // Fix the recipients at completion time. Turning notices on later must not replay history.
  if (kind === 'completed') await client.query(`INSERT INTO completion_notice_sends (event_id, member_id, attempted_at, claimed_until)
    SELECT $1, m.id, $2, $2::timestamptz - interval '1 millisecond' FROM members m
    JOIN plants p ON p.household_id = m.household_id AND p.id = $3
    WHERE m.completion_notices_enabled AND m.id <> $4 AND ${visiblePlantSql('m.id')}`,
  [event.rows[0].id, at, plantId, identity.member.id]);
  return event.rows[0].id as string;
}

async function waterWithLiquidFertilizer(client: pg.PoolClient, identity: Identity, plant: Awaited<ReturnType<typeof getPlant>>, taskType: string, eventId: string, at: Date, water: ScheduledTask, { batchId = null }: { batchId?: string | null } = {}) {
  if (taskType === 'fertilize' && plant.fertilizer_type === 'liquid') await recordEvent(client, identity, plant.id, 'water', 'completed', randomUUID(), at, dueOf(water), { batchId, linkedEventId: eventId });
}

export async function completeAll(identity: Identity, plantId: string, requestId: string) {
  const { events } = await careAction(identity, plantId, requestId, 'complete-all', {}, async (client, [plant], at) => {
    const today = calendarDate(at);
    const tasks = (await schedulePlantTasks([plant], identity.household.id, today, identity.language, client, true)).schedules.get(plantId)!;
    let watered = false;
    let fertilizeEventId: string | undefined;
    for (const type of [...taskTypes, ...Object.keys(tasks.customTasks)]) {
      const task = taskIn(tasks, type)!;
      if (task.status !== 'due' || task.dueOn > today) continue;
      const eventId = await recordEvent(client, identity, plantId, type, 'completed', randomUUID(), at, task.dueOn, { batchId: requestId });
      if (type === 'water') watered = true;
      if (type === 'fertilize') fertilizeEventId = eventId;
    }
    if (!watered && fertilizeEventId) await waterWithLiquidFertilizer(client, identity, plant, 'fertilize', fertilizeEventId, at, tasks.water, { batchId: requestId });
  });
  return { events, tasks: await plantTasks(plantId, identity.household.id, identity.language) };
}

export async function completeType(identity: Identity, taskType: string, requestId: string) {
  if (isUuid(taskType)) taskType = taskType.toLowerCase();
  if (!taskTypes.includes(taskType as TaskType) && !isUuid(taskType)) error(404, 'Task type not found');
  const household = identity.household.id;
  const { filter } = await taskViewSettings(identity);
  const { action, events } = await careAction(identity, null, requestId, 'complete-type', { task_type: taskType }, async (client, _, at) => {
    if (isUuid(taskType)) await getCustomTask(household, taskType, client);
    const plants = await client.query(plantSelectFor(identity.language) + ` WHERE p.household_id = $1 AND ${visiblePlantSql('$2')} AND ($3 = 'all' OR t.location = $3) ORDER BY p.id FOR UPDATE OF p`, [household, identity.member.id, filter]);
    const today = calendarDate(at);
    const { schedules } = await schedulePlantTasks(plants.rows, household, today, identity.language, client, true);
    for (const plant of plants.rows) {
      const tasks = schedules.get(plant.id)!;
      const task = taskIn(tasks, taskType);
      if (task?.status === 'due' && task.dueOn <= today) {
        const eventId = await recordEvent(client, identity, plant.id, taskType, 'completed', randomUUID(), at, task.dueOn, { batchId: requestId });
        await waterWithLiquidFertilizer(client, identity, plant, taskType, eventId, at, tasks.water, { batchId: requestId });
      }
    }
  });
  return { batch_id: action.request_id as string, events };
}

export async function repot(identity: Identity, plantId: string, input: Record<string, unknown>) {
  await getPlant(plantId, identity.household.id, identity.language);
  const errors = potErrors(input);
  if (Object.keys(errors).length) return json({ errors }, { status: 422 });
  if (typeof input.request_id !== 'string') error(422, 'Send a UUID request_id.');
  const pot = { pot_type: input.pot_type, pot_size_cm: input.pot_size_cm ?? null, soil_type: input.soil_type };
  const requestId = input.request_id;
  const { events } = await careAction(identity, plantId, requestId, 'repot', pot, async (client, [plant], at) => {
    const previous = { pot_type: plant.pot_type, pot_size_cm: plant.pot_size_cm, soil_type: plant.soil_type };
    const task = (await schedulePlantTasks([plant], identity.household.id, calendarDate(at), identity.language, client, true)).schedules.get(plantId)!.repot;
    await client.query('UPDATE plants SET pot_type = $1, pot_size_cm = $2, soil_type = $3 WHERE id = $4 AND household_id = $5', [pot.pot_type, pot.pot_size_cm, pot.soil_type, plantId, identity.household.id]);
    await recordEvent(client, identity, plantId, 'repot', 'completed', requestId, at, dueOf(task), { previousPot: previous });
  });
  return json({ event: events[0], task: (await plantTasks(plantId, identity.household.id, identity.language)).repot });
}


export async function complete(identity: Identity, plantId: string, taskType: string, requestId: string, kind: CareEvent['kind'] = 'completed') {
  if (!taskTypes.includes(taskType as TaskType) && !isUuid(taskType)) error(404, 'Task type not found');
  const { events } = await careAction(identity, plantId, requestId, taskType + ':' + kind, {}, async (client, [plant], at) => {
    const tasks = (await schedulePlantTasks([plant], identity.household.id, calendarDate(at), identity.language, client, true)).schedules.get(plantId)!;
    const task = taskIn(tasks, taskType);
    if (!task) error(404, 'Custom task not found for this plant');
    if (task.status === 'not_applicable') error(422, 'This task is not applicable.');
    if (kind === 'snoozed' && task.dueOn > calendarDate(at)) error(409, 'Only due or late tasks can be snoozed.');
    const until = kind === 'completed' ? null : postponedUntil(kind, calendarDate(at), task.intervalDays);
    const eventId = await recordEvent(client, identity, plantId, taskType, kind, requestId, at, task.dueOn, { until });
    if (kind === 'completed') await waterWithLiquidFertilizer(client, identity, plant, taskType, eventId, at, tasks.water);
  });
  const tasks = await plantTasks(plantId, identity.household.id, identity.language);
  return { event: events[0], linked_events: events.filter(event => event.linked_event_id === events[0]?.id), task: taskIn(tasks, taskType) };
}

export async function completeRain(identity: Identity, requestId: string) {
  const household = identity.household.id;
  const { action, events } = await careAction(identity, null, requestId, 'rain', {}, async (client, _, at) => {
    const cache = await client.query("SELECT data FROM weather_cache WHERE household_id = $1 AND kind = 'forecast'", [household]);
    const days: ForecastDay[] = cache.rows[0]?.data ?? [];
    const rain = await client.query("SELECT id, batch_id FROM weather_events WHERE household_id = $1 AND kind = 'rain' AND resolved_at IS NULL AND starts_on = ANY($2::date[]) ORDER BY starts_on DESC", [household, recentRain(days, calendarDate(at)).map(day => day.date)]);
    if (!rain.rowCount) error(409, { message: 'No recent rain report for outdoor plants.', reason: 'rain_report_not_ready' });
    const existing = await client.query('SELECT request_id FROM care_actions WHERE request_id = ANY($1::uuid[]) AND household_id = $2 AND undone_at IS NULL ORDER BY created_at DESC, request_id DESC LIMIT 1', [rain.rows.map(row => row.batch_id).filter(Boolean), household]);
    const batchId: string = existing.rows[0]?.request_id ?? requestId;
    if (existing.rowCount) {
      await client.query('UPDATE care_actions SET batch_id = $2 WHERE request_id = $1', [requestId, batchId]);
    } else {
      const locked = await client.query("SELECT p.id FROM plants p JOIN sites s ON s.id = p.site_id WHERE p.household_id = $1 AND p.status = 'active' AND s.location = 'outdoor' ORDER BY p.id FOR UPDATE OF p, s", [household]);
      if (!locked.rowCount) error(409, 'No active outdoor plants.');
      const plants = await client.query(plantSelectFor(identity.language) + ' WHERE p.id = ANY($1::uuid[]) AND p.household_id = $2 ORDER BY p.id', [locked.rows.map(row => row.id), household]);
      const { schedules } = await schedulePlantTasks(plants.rows, household, calendarDate(at), identity.language, client, true);
      for (const plant of plants.rows) await recordEvent(client, identity, plant.id, 'water', 'completed', randomUUID(), at, dueOf(schedules.get(plant.id)!.water), { batchId });
    }
    await client.query('UPDATE weather_events SET batch_id = $2 WHERE id = ANY($1::uuid[])', [rain.rows.map(row => row.id), batchId]);
  });
  return { batchId: action.request_id as string, plantCount: events.length, undoneAt: action.undone_at?.toISOString() ?? null };
}

export async function undoBatch(identity: Identity, batchId: string) {
  await requireOwnUndo(identity, batchId, true);
  if (!isUuid(batchId)) error(404, 'Task batch not found');
  const household = identity.household.id;
  const result = await withSnapshot(async (client) => {
    await client.query('SELECT id FROM households WHERE id = $1 FOR NO KEY UPDATE', [household]);
    // Plant locks come before the action lock, matching careAction and lifecycle commands.
    const plants = await client.query('SELECT p.id FROM plants p WHERE p.household_id = $2 AND (p.id IN (SELECT plant_id FROM care_events WHERE batch_id = $1 AND household_id = $2) OR p.id IN (SELECT plant_id FROM care_actions WHERE request_id = $1 AND household_id = $2)) ORDER BY p.id FOR UPDATE', [batchId, household]);
    const batch = await client.query("SELECT undone_at FROM care_actions WHERE request_id = $1 AND household_id = $2 AND action IN ('complete-all', 'complete-type', 'rain') AND batch_id IS NULL FOR UPDATE", [batchId, household]);
    if (!batch.rowCount) error(404, 'Task batch not found');
    await requireUndo(identity, batchId, true, client);
    const plantIds = plants.rows.map(plant => plant.id);
    const before = await pendingOccurrences(client, identity, plantIds);
    const time = now();
    const reversed = await client.query('UPDATE care_events SET undone_at = $1, undone_by = $2 WHERE batch_id = $3 AND household_id = $4 AND undone_at IS NULL RETURNING id, plant_id, task_type, custom_task_id', [time, identity.member.id, batchId, household]);
    await client.query('UPDATE care_actions SET undone_at = $1, undone_by = $2 WHERE request_id = $3 AND household_id = $4', [time, identity.member.id, batchId, household]);
    return { batchId, undoneAt: time.toISOString(), restored: restoredBy(before, await pendingOccurrences(client, identity, plantIds), reversed.rows) };
  });
  return { ...result, ...await undoneLists(identity) };
}

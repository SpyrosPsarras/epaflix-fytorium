import { error } from '@sveltejs/kit';
import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { schedule, taskTypes, type TaskType, type Task, type ScheduleInput } from '$lib/schedule/engine';
import type { CareEvent, ListedTask, TaskList } from '$lib/tasks';
import type { Identity } from './auth';
import { getPlant, isUuid, plantSelect } from './plants';
import { db } from './db';
import { now, calendarDate } from './clock';
import type { ForecastDay } from '$lib/weather';
import { recentRain } from './weather/rain';
import { householdSettings } from './care-settings';
import { weatherReport } from './weather/cache';
import { effectiveCareProfiles } from './care/catalogue';

export async function history(plantId: string, household: string): Promise<CareEvent[]> {
  await getPlant(plantId, household);
  const result = await db().query('SELECT * FROM care_events WHERE plant_id = $1 AND household_id = $2 ORDER BY occurred_at DESC, id DESC', [plantId, household]);
  return result.rows.map(event => ({ ...event, occurred_on: calendarDate(event.occurred_at) }));
}

async function schedulePlantTasks(plants: Awaited<ReturnType<typeof getPlant>>[], household: string, today: string) {
  const plantIds = plants.map(plant => plant.id);
  const [sites, events, intervals, settings, profiles, weather] = await Promise.all([
    db().query('SELECT * FROM sites WHERE id = ANY($1::uuid[]) AND household_id = $2', [plants.map(plant => plant.site_id), household]),
    db().query('SELECT plant_id, task_type, max(occurred_at) AS last_completed FROM care_events WHERE plant_id = ANY($1::uuid[]) AND household_id = $2 AND undone_at IS NULL AND kind = $3 GROUP BY plant_id, task_type', [plantIds, household, 'completed']),
    db().query('SELECT plant_id, task_type, interval_days FROM plant_task_intervals WHERE plant_id = ANY($1::uuid[]) AND household_id = $2', [plantIds, household]),
    householdSettings(household),
    effectiveCareProfiles(plants.map(plant => plant.gbif_key), household),
    weatherReport(household),
  ]);
  const sitesById = new Map(sites.rows.map(site => [site.id, site]));
  const historyByPlant: Record<string, ScheduleInput['history']> = {};
  for (const event of events.rows) {
    (historyByPlant[event.plant_id] ??= {})[event.task_type as TaskType] = { lastCompletedOn: calendarDate(event.last_completed) };
  }
  const intervalsByPlant: Record<string, ScheduleInput['customIntervals']> = {};
  for (const interval of intervals.rows) {
    (intervalsByPlant[interval.plant_id] ??= {})[interval.task_type as TaskType] = interval.interval_days;
  }
  return new Map(plants.map(plant => [plant.id, schedule({
    careProfile: profiles.get(plant.gbif_key)!, site: sitesById.get(plant.site_id), pot: plant, plant, household: settings,
    history: historyByPlant[plant.id] ?? {}, customIntervals: intervalsByPlant[plant.id] ?? {},
    addedOn: calendarDate(plant.created_at), today,
    weather: sitesById.get(plant.site_id)?.location === 'outdoor' ? weather.snapshot ?? undefined : undefined,
  })]));
}

export async function plantTasks(plantId: string, household: string): Promise<Partial<Record<TaskType, Task>>> {
  const plant = await getPlant(plantId, household);
  if (plant.status === 'graveyard') return {};
  return (await schedulePlantTasks([plant], household, calendarDate(now()))).get(plant.id)!;
}

export async function todayTasks(household: string): Promise<TaskList> {
  const today = calendarDate(now());
  const plants = await db().query(plantSelect + " WHERE p.household_id = $1 AND p.status = 'active' ORDER BY display_name, p.id", [household]);
  const groups = Object.fromEntries(taskTypes.map(type => [type, new Array<ListedTask>()])) as TaskList['groups'];
  const schedules = await schedulePlantTasks(plants.rows, household, today);
  for (const plant of plants.rows) {
    const tasks = schedules.get(plant.id)!;
    for (const taskType of taskTypes) {
      const task = tasks[taskType];
      if (task.status === 'due' && task.dueOn <= today) groups[taskType].push({ ...task, plant_id: plant.id, plant_name: plant.display_name, site_name: plant.site_name, task_type: taskType });
    }
  }
  for (const tasks of Object.values(groups)) tasks.sort((a, b) => a.status === 'due' && b.status === 'due' ? a.dueOn.localeCompare(b.dueOn) : 0);
  return { today, groups };
}

export async function undo(identity: Identity, eventId: string) {
  if (!isUuid(eventId)) error(404, 'Care event not found');
  const household = identity.household.id;
  const client = await db().connect();
  let result;
  try {
    await client.query('BEGIN');
    const existing = await client.query('SELECT plant_id, batch_id FROM care_events WHERE id = $1 AND household_id = $2', [eventId, household]);
    if (!existing.rowCount) error(404, 'Care event not found');
    if (existing.rows[0].batch_id) error(409, 'Undo this rain action as one batch.');
    const plant = await client.query('SELECT status FROM plants WHERE id = $1 AND household_id = $2 FOR UPDATE', [existing.rows[0].plant_id, household]);
    if (!plant.rowCount) error(404, 'Plant not found');
    if (plant.rows[0].status !== 'active') error(409, 'Graveyard plants have no tasks. Restore the plant first.');
    result = await client.query('UPDATE care_events SET undone_at = $1, undone_by = $2 WHERE id = $3 AND household_id = $4 AND undone_at IS NULL RETURNING *', [now(), identity.member.id, eventId, household]);
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
  const event = result.rows[0];
  if (!event) {
    const existing = await db().query('SELECT id FROM care_events WHERE id = $1 AND household_id = $2', [eventId, household]);
    if (!existing.rowCount) error(404, 'Care event not found');
    error(409, 'Care event already undone');
  }
  return { event: { ...event, occurred_on: calendarDate(event.occurred_at) }, task: (await plantTasks(event.plant_id, household))[event.task_type as TaskType] };
}

export async function complete(identity: Identity, plantId: string, taskType: TaskType, requestId: string) {
  const household = identity.household.id;
  await getPlant(plantId, household);
  if (!taskTypes.includes(taskType)) error(404, 'Task type not found');
  if (!isUuid(requestId)) error(422, 'Send a UUID request_id.');
  const member = await db().query('SELECT display_name FROM members WHERE id = $1 AND household_id = $2', [identity.member.id, household]);
  const client = await db().connect();
  let result;
  try {
    await client.query('BEGIN');
    const plant = await client.query('SELECT status FROM plants WHERE id = $1 AND household_id = $2 FOR UPDATE', [plantId, household]);
    if (!plant.rowCount) error(404, 'Plant not found');
    if (plant.rows[0].status !== 'active') error(409, 'Graveyard plants have no tasks. Restore the plant first.');
    result = await insertCompletion(client, identity, plantId, taskType, requestId, member.rows[0].display_name);
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
  const event = result.rows[0] ?? (await db().query('SELECT * FROM care_events WHERE request_id = $1 AND household_id = $2', [requestId, household])).rows[0];
  if (!event || event.plant_id !== plantId || event.task_type !== taskType || event.by_member !== identity.member.id) error(409, 'request_id already used for another action.');
  return { event: { ...event, occurred_on: calendarDate(event.occurred_at) }, task: (await plantTasks(plantId, household))[taskType] };
}

// Single and rain completions share the care-event write, including member and server-clock attribution.
async function insertCompletion(client: PoolClient, identity: Identity, plantId: string, taskType: TaskType, requestId: string, memberName: string, batchId: string | null = null) {
  return client.query(
    "INSERT INTO care_events (household_id, plant_id, task_type, kind, occurred_at, by_member, by_name, request_id, batch_id) VALUES ($1, $2, $3, 'completed', $4, $5, $6, $7, $8) ON CONFLICT (request_id) DO NOTHING RETURNING *",
    [identity.household.id, plantId, taskType, now(), identity.member.id, memberName, requestId, batchId],
  );
}
export async function completeRain(identity: Identity, requestId: string) {
  if (!isUuid(requestId)) error(422, 'Send a UUID request_id.');
  const household = identity.household.id;
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    // Location changes, reconciliation and undo use the same household lock.
    await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
    let batch = (await client.query('SELECT b.*, r.by_member AS request_member FROM task_batch_requests r JOIN task_batches b ON b.id = r.batch_id WHERE r.request_id = $1', [requestId])).rows[0];
    if (batch && (batch.household_id !== household || batch.request_member !== identity.member.id)) error(409, 'request_id already used for another action.');
    let rainIds: string[] = [];
    if (!batch) {
      const cache = await client.query("SELECT data FROM weather_cache WHERE household_id = $1 AND kind = 'forecast'", [household]);
      const days: ForecastDay[] = cache.rows[0]?.data ?? [];
      const rain = await client.query("SELECT e.id, e.batch_id FROM weather_events e WHERE e.household_id = $1 AND e.kind = 'rain' AND e.resolved_at IS NULL AND e.starts_on = ANY($2::date[]) ORDER BY e.starts_on DESC", [household, recentRain(days, calendarDate(now())).map(day => day.date)]);
      rainIds = rain.rows.map(row => row.id);
      if (!rainIds.length) error(409, 'No recent rain report for outdoor plants.');
      batch = (await client.query('SELECT * FROM task_batches WHERE id = ANY($1::uuid[]) AND household_id = $2 AND undone_at IS NULL ORDER BY created_at DESC LIMIT 1', [rain.rows.map(row => row.batch_id).filter(Boolean), household])).rows[0];
    }
    if (!batch) {
      const claimed = await client.query("INSERT INTO task_batches (household_id, kind, request_id, by_member, created_at) VALUES ($1, 'rain', $2, $3, $4) ON CONFLICT (request_id) DO NOTHING RETURNING *", [household, requestId, identity.member.id, now()]);
      batch = claimed.rows[0];
      if (!batch) error(409, 'request_id already used for another action.');
      const plants = await client.query("SELECT p.id FROM plants p JOIN sites s ON s.id = p.site_id WHERE p.household_id = $1 AND p.status = 'active' AND s.location = 'outdoor' ORDER BY p.id FOR UPDATE OF p, s", [household]);
      if (!plants.rowCount) error(409, 'No active outdoor plants.');
      const member = await client.query('SELECT display_name FROM members WHERE id = $1 AND household_id = $2', [identity.member.id, household]);
      for (const plant of plants.rows) await insertCompletion(client, identity, plant.id, 'water', randomUUID(), member.rows[0].display_name, batch.id);
    }
    if (rainIds.length && !batch.undone_at) await client.query('UPDATE weather_events SET batch_id = $2 WHERE id = ANY($1::uuid[])', [rainIds, batch.id]);
    // Remember every tap UUID, including taps that returned another member's existing batch.
    await client.query('INSERT INTO task_batch_requests (request_id, batch_id, by_member) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING', [requestId, batch.id, identity.member.id]);
    const replay = (await client.query('SELECT batch_id, by_member FROM task_batch_requests WHERE request_id = $1', [requestId])).rows[0];
    if (replay.batch_id !== batch.id || replay.by_member !== identity.member.id) error(409, 'request_id already used for another action.');
    const count = await client.query('SELECT count(*)::integer AS n FROM care_events WHERE batch_id = $1 AND household_id = $2', [batch.id, household]);
    await client.query('COMMIT');
    return { batchId: batch.id as string, plantCount: count.rows[0].n as number, undoneAt: batch.undone_at?.toISOString() ?? null };
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
}
export async function undoBatch(identity: Identity, batchId: string) {
  if (!isUuid(batchId)) error(404, 'Task batch not found');
  const household = identity.household.id;
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
    const batch = await client.query('SELECT * FROM task_batches WHERE id = $1 AND household_id = $2 FOR UPDATE', [batchId, household]);
    if (!batch.rowCount) error(404, 'Task batch not found');
    if (batch.rows[0].undone_at) error(409, 'Task batch already undone');
    const plants = await client.query('SELECT p.status FROM plants p WHERE p.household_id = $2 AND p.id IN (SELECT plant_id FROM care_events WHERE batch_id = $1 AND household_id = $2) ORDER BY p.id FOR UPDATE', [batchId, household]);
    if (plants.rows.some(plant => plant.status !== 'active')) error(409, 'Restore graveyard plants before undoing their care.');
    const time = now();
    await client.query('UPDATE care_events SET undone_at = $1, undone_by = $2 WHERE batch_id = $3 AND household_id = $4 AND undone_at IS NULL', [time, identity.member.id, batchId, household]);
    await client.query('UPDATE task_batches SET undone_at = $1, undone_by = $2 WHERE id = $3 AND household_id = $4', [time, identity.member.id, batchId, household]);
    await client.query('COMMIT');
    return { batchId, undoneAt: time.toISOString() };
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
}

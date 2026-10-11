import { error } from '@sveltejs/kit';
import { schedule, taskTypes, type TaskType } from '$lib/schedule/engine';
import type { CareEvent, ListedTask, TaskList } from '$lib/tasks';
import type { Identity } from './auth';
import { getPlant, isUuid, plantSelect } from './plants';
import { db } from './db';
import { config } from './config';
import { now } from './clock';

// Intl owns time-zone and DST conversion; the engine receives calendar dates only.
function calendarDate(at: Date | string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: config().householdTimezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(at));
}

export async function history(plantId: string, household: string): Promise<CareEvent[]> {
  await getPlant(plantId, household);
  const result = await db().query('SELECT * FROM care_events WHERE plant_id = $1 AND household_id = $2 ORDER BY occurred_at DESC, id DESC', [plantId, household]);
  return result.rows.map(event => ({ ...event, occurred_on: calendarDate(event.occurred_at) }));
}

async function schedulePlantTasks(plant: Awaited<ReturnType<typeof getPlant>>, household: string, today: string) {
  const site = await db().query('SELECT * FROM sites WHERE id = $1 AND household_id = $2', [plant.site_id, household]);
  const events = await db().query('SELECT task_type, max(occurred_at) AS last_completed FROM care_events WHERE plant_id = $1 AND household_id = $2 AND undone_at IS NULL AND kind = $3 GROUP BY task_type', [plant.id, household, 'completed']);
  return schedule({ careProfile: {}, site: site.rows[0], pot: plant, plant,
    history: Object.fromEntries(events.rows.map(event => [event.task_type, { lastCompletedOn: calendarDate(event.last_completed) }])),
    customIntervals: {}, addedOn: calendarDate(plant.created_at), today });
}

export async function plantTasks(plantId: string, household: string) {
  return schedulePlantTasks(await getPlant(plantId, household), household, calendarDate(now()));
}

export async function todayTasks(household: string): Promise<TaskList> {
  const today = calendarDate(now());
  const plants = await db().query(plantSelect + ' WHERE p.household_id = $1 ORDER BY display_name, p.id', [household]);
  const groups = Object.fromEntries(taskTypes.map(type => [type, new Array<ListedTask>()])) as TaskList['groups'];
  for (const plant of plants.rows) {
    const tasks = await schedulePlantTasks(plant, household, today);
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
  // The predicate makes racing undo taps atomic. No care record is deleted.
  const result = await db().query('UPDATE care_events SET undone_at = $1, undone_by = $2 WHERE id = $3 AND household_id = $4 AND undone_at IS NULL RETURNING *', [now(), identity.member.id, eventId, household]);
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
  const result = await db().query(
    "INSERT INTO care_events (household_id, plant_id, task_type, kind, occurred_at, by_member, by_name, request_id) VALUES ($1, $2, $3, 'completed', $4, $5, $6, $7) ON CONFLICT (request_id) DO NOTHING RETURNING *",
    [household, plantId, taskType, now(), identity.member.id, member.rows[0].display_name, requestId],
  );
  const event = result.rows[0] ?? (await db().query('SELECT * FROM care_events WHERE request_id = $1 AND household_id = $2', [requestId, household])).rows[0];
  if (!event || event.plant_id !== plantId || event.task_type !== taskType || event.by_member !== identity.member.id) error(409, 'request_id already used for another action.');
  return { event: { ...event, occurred_on: calendarDate(event.occurred_at) }, task: (await plantTasks(plantId, household))[taskType] };
}

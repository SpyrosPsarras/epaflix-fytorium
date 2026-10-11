import { error } from '@sveltejs/kit';
import type { TaskViewSettings } from '$lib/tasks';
import type { Identity } from './auth';
import { db } from './db';

export async function taskViewSettings(identity: Identity): Promise<TaskViewSettings> {
  const result = await db().query('SELECT task_sort AS sort, task_filter AS filter, task_show_completed AS show_completed FROM members WHERE id = $1 AND household_id = $2', [identity.member.id, identity.household.id]);
  return result.rows[0];
}
export async function saveTaskViewSettings(identity: Identity, input: Record<string, unknown>) {
  if (typeof input.sort !== 'string' || !['task', 'plant', 'site'].includes(input.sort) || typeof input.filter !== 'string' || !['all', 'indoor', 'outdoor'].includes(input.filter) || typeof input.show_completed !== 'boolean') error(422, 'Choose a task sort, location filter and show_completed true or false.');
  await db().query('UPDATE members SET task_sort = $1, task_filter = $2, task_show_completed = $3 WHERE id = $4 AND household_id = $5', [input.sort, input.filter, input.show_completed, identity.member.id, identity.household.id]);
  return taskViewSettings(identity);
}

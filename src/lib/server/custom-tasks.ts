import { error, json } from '@sveltejs/kit';
import { db } from './db';
import { now } from './clock';
import { getPlant, isUuid } from './plants';
import type { CustomTask } from '$lib/custom-tasks';

export async function checkTarget(household: string, plantId: unknown, siteId: unknown) {
  if ((plantId != null) === (siteId != null)) error(422, 'Choose exactly one plant or site.');
  if (plantId != null) {
    if (typeof plantId !== 'string' || !isUuid(plantId)) error(422, 'Choose a valid plant.');
    await getPlant(plantId, household, 'en');
  } else {
    if (typeof siteId !== 'string' || !isUuid(siteId)) error(422, 'Choose a valid site.');
    const result = await db().query('SELECT id FROM sites WHERE id = $1 AND household_id = $2', [siteId, household]);
    if (!result.rowCount) error(404, 'Site not found');
  }
}

export async function customTasks(household: string, plantId: string | null, siteId: string | null): Promise<CustomTask[]> {
  await checkTarget(household, plantId, siteId);
  const result = await db().query('SELECT * FROM custom_tasks WHERE household_id = $1 AND deleted_at IS NULL AND (plant_id = $2 OR site_id = $3) ORDER BY name, id', [household, plantId, siteId]);
  return result.rows;
}

function fields(input: Record<string, unknown>) {
  const errors: Record<string, string> = {};
  const name = typeof input.name === 'string' ? input.name.trim() : '';
  if (!name || [...name].length > 60) errors.name = 'Enter a name from 1 to 60 characters.';
  if (typeof input.interval_days !== 'number' || !Number.isInteger(input.interval_days) || input.interval_days < 1 || input.interval_days > 365) errors.interval_days = 'Enter whole days from 1 to 365.';
  return { name, errors };
}

export async function createCustomTask(household: string, input: Record<string, unknown>) {
  const { name, errors } = fields(input);
  if (Object.keys(errors).length) return json({ errors }, { status: 422 });
  await checkTarget(household, input.plant_id, input.site_id);
  const result = await db().query('INSERT INTO custom_tasks (household_id, name, interval_days, plant_id, site_id, created_at) VALUES ($1,$2,$3,$4,$5,$6) RETURNING *', [household, name, input.interval_days, input.plant_id ?? null, input.site_id ?? null, now()]);
  return json(result.rows[0], { status: 201 });
}

export async function getCustomTask(household: string, id: string): Promise<CustomTask> {
  if (!isUuid(id)) error(404, 'Custom task not found');
  const result = await db().query('SELECT * FROM custom_tasks WHERE id = $1 AND household_id = $2 AND deleted_at IS NULL', [id, household]);
  if (!result.rowCount) error(404, 'Custom task not found');
  return result.rows[0];
}

export async function editCustomTask(household: string, id: string, input: Record<string, unknown>) {
  const task = await getCustomTask(household, id);
  const { name, errors } = fields(input);
  if (('plant_id' in input && input.plant_id !== task.plant_id) || ('site_id' in input && input.site_id !== task.site_id)) errors.target = 'The task target cannot change.';
  if (Object.keys(errors).length) return json({ errors }, { status: 422 });
  const result = await db().query('UPDATE custom_tasks SET name = $1, interval_days = $2 WHERE id = $3 AND household_id = $4 AND deleted_at IS NULL RETURNING *', [name, input.interval_days, id, household]);
  if (!result.rowCount) error(404, 'Custom task not found');
  return json(result.rows[0]);
}

export async function deleteCustomTask(household: string, id: string) {
  await getCustomTask(household, id);
  await db().query('UPDATE custom_tasks SET deleted_at = $1 WHERE id = $2 AND household_id = $3', [now(), id, household]);
  return new Response(null, { status: 204 });
}

import { error, json } from '@sveltejs/kit';
import type pg from 'pg';
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

export async function customTasks(household: string, plantId: string | null, siteId: string | null, language = 'en'): Promise<CustomTask[]> {
  if (plantId == null && siteId == null) return householdCustomTasks(household, language);
  await checkTarget(household, plantId, siteId);
  const result = await db().query('SELECT * FROM custom_tasks WHERE household_id = $1 AND deleted_at IS NULL AND (plant_id = $2 OR site_id = $3) ORDER BY name, id', [household, plantId, siteId]);
  return result.rows;
}

// Every live task with its target named, sites first, for the app's one list.
async function householdCustomTasks(household: string, language: string): Promise<CustomTask[]> {
  const result = await db().query(
    `SELECT t.*, coalesce(plant_display_name(p.nickname, p.gbif_key, $2), s.name) AS target_name,
       ps.name AS target_site_name, p.status AS target_status,
       CASE WHEN s.id IS NULL THEN NULL ELSE (SELECT count(*)::integer FROM plants a WHERE a.site_id = s.id AND a.household_id = $1 AND a.status = 'active') END AS target_plant_count
     FROM custom_tasks t
     LEFT JOIN plants p ON p.id = t.plant_id AND p.household_id = t.household_id
     LEFT JOIN sites ps ON ps.id = p.site_id AND ps.household_id = t.household_id
     LEFT JOIN sites s ON s.id = t.site_id AND s.household_id = t.household_id
     WHERE t.household_id = $1 AND t.deleted_at IS NULL AND (p.id IS NOT NULL OR s.id IS NOT NULL)
     ORDER BY t.site_id IS NULL, target_name, t.name, t.id`,
    [household, language],
  );
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

export async function getCustomTask(household: string, id: string, database: pg.Pool | pg.PoolClient = db()): Promise<CustomTask> {
  if (!isUuid(id)) error(404, 'Custom task not found');
  const result = await database.query('SELECT * FROM custom_tasks WHERE id = $1 AND household_id = $2 AND deleted_at IS NULL', [id, household]);
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

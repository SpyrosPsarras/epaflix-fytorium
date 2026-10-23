import { error } from '@sveltejs/kit';
import { taskTypes, type TaskType } from '$lib/schedule/engine';
import { db, withTransaction } from './db';
import { getPlant } from './plants';

export type PlantCareSettings = { customIntervals: Partial<Record<TaskType, number>> };

export async function plantCareSettings(plantId: string, household: string): Promise<PlantCareSettings> {
  await getPlant(plantId, household, 'en');
  const intervals = await db().query('SELECT task_type, interval_days FROM plant_task_intervals WHERE plant_id = $1 AND household_id = $2', [plantId, household]);
  return { customIntervals: Object.fromEntries(intervals.rows.map(row => [row.task_type, row.interval_days])) };
}

export async function savePlantCareSettings(plantId: string, household: string, input: Record<string, unknown>): Promise<PlantCareSettings> {
  await getPlant(plantId, household, 'en');
  const intervals = input.customIntervals;
  if (!intervals || typeof intervals !== 'object' || Array.isArray(intervals)) error(422, 'customIntervals must be an object keyed by task type.');
  for (const [type, days] of Object.entries(intervals)) {
    if (!taskTypes.some(task => task === type)) error(422, 'Unknown task type: ' + type);
    if (typeof days !== 'number' || !Number.isInteger(days) || days < 1 || days > 365) error(422, 'Intervals must be whole days from 1 to 365.');
  }
  await withTransaction(async (client) => {
    await client.query('DELETE FROM plant_task_intervals WHERE plant_id = $1 AND household_id = $2', [plantId, household]);
    for (const [type, days] of Object.entries(intervals)) await client.query('INSERT INTO plant_task_intervals (plant_id, household_id, task_type, interval_days) VALUES ($1, $2, $3, $4)', [plantId, household, type, days]);
  });
  return plantCareSettings(plantId, household);
}

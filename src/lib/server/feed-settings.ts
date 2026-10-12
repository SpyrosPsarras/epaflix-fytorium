import { error } from '@sveltejs/kit';
import { feedKinds, type FeedSettings } from '$lib/feed';
import type { Identity } from './auth';
import { db } from './db';

export async function feedSettings(identity: Identity): Promise<FeedSettings> {
  const result = await db().query('SELECT feed_late_tasks AS late_tasks, feed_current_weather AS current_weather, feed_weather_alerts AS weather_alerts, feed_plant_warnings AS plant_warnings FROM members WHERE id = $1 AND household_id = $2', [identity.member.id, identity.household.id]);
  return result.rows[0];
}
export async function saveFeedSettings(identity: Identity, input: Record<string, unknown>) {
  if (Object.keys(input).length !== feedKinds.length || feedKinds.some(kind => typeof input[kind] !== 'boolean')) error(422, 'Set each feed card type to true or false.');
  await db().query('UPDATE members SET feed_late_tasks = $1, feed_current_weather = $2, feed_weather_alerts = $3, feed_plant_warnings = $4 WHERE id = $5 AND household_id = $6', [...feedKinds.map(kind => input[kind]), identity.member.id, identity.household.id]);
  return feedSettings(identity);
}

import { error } from '@sveltejs/kit';
import type { Identity } from '../auth';
import { db } from '../db';
export type NotificationSettings = { reminder_time: string; reminders_enabled: boolean; weather_alerts_enabled: boolean };
export async function notificationSettings(identity: Identity): Promise<NotificationSettings> {
  const result = await db().query('SELECT reminder_time, reminders_enabled, weather_alerts_enabled FROM members WHERE id = $1 AND household_id = $2', [identity.member.id, identity.household.id]);
  return result.rows[0];
}
export async function saveNotificationSettings(identity: Identity, input: Record<string, unknown>) {
  if (typeof input.reminder_time !== 'string' || !/^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(input.reminder_time) || typeof input.reminders_enabled !== 'boolean' || typeof input.weather_alerts_enabled !== 'boolean') error(422, 'Choose a reminder time HH:MM and on/off switches');
  await db().query('UPDATE members SET reminder_time = $1, reminders_enabled = $2, weather_alerts_enabled = $3 WHERE id = $4 AND household_id = $5', [input.reminder_time, input.reminders_enabled, input.weather_alerts_enabled, identity.member.id, identity.household.id]);
  return notificationSettings(identity);
}

import { error, json } from '@sveltejs/kit';
import { config } from '$lib/server/config';
import { calendarDate, now } from '$lib/server/clock';
import { db } from '$lib/server/db';
import { runPushWorkerOnce } from '$lib/server/notifications/worker';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
// Reset only this member's current-day lease for rerunnable emulator fixtures.
export const DELETE: RequestHandler = async ({ locals, request }) => {
  if (!config().testClockEnabled) error(404, 'Not found');
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await db().query('DELETE FROM daily_reminder_sends WHERE member_id = $1 AND local_date = $2', [locals.identity.member.id, calendarDate(now())]);
  return json({ reset: true });
};
export const POST: RequestHandler = async ({ locals, request }) => {
  if (!config().testClockEnabled) error(404, 'Not found');
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await runPushWorkerOnce();
  return json({ processed: true });
};

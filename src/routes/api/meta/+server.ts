import { json } from '@sveltejs/kit';
import { config } from '$lib/server/config';
import { calendarDate, now } from '$lib/server/clock';
import { requirePermission } from '$lib/server/policy';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = ({ locals }) => {
  const identity = requirePermission(locals.identity, 'self');
  const settings = config();
  return json({ minAppVersion: settings.minAppVersion, serverVersion: settings.serverVersion,
    today: calendarDate(now()), timezone: settings.householdTimezone, role: identity.member.role });
};

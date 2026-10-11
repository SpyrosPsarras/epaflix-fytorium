import { error, json } from '@sveltejs/kit';
import { notificationSettings, saveNotificationSettings } from '$lib/server/notifications/settings';
import { readJsonObject } from '$lib/server/json';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await notificationSettings(locals.identity));
};
export const PUT: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await saveNotificationSettings(locals.identity, await readJsonObject(request)));
};

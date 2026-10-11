import { error, json } from '@sveltejs/kit';
import { feedSettings, saveFeedSettings } from '$lib/server/feed-settings';
import { readTaskInput } from '$lib/server/tasks';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await feedSettings(locals.identity));
};
export const PUT: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await saveFeedSettings(locals.identity, await readTaskInput(request)));
};

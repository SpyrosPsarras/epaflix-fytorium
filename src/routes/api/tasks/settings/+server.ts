import { error, json } from '@sveltejs/kit';
import { taskViewSettings, saveTaskViewSettings } from '$lib/server/task-view-settings';
import { readTaskInput } from '$lib/server/tasks';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await taskViewSettings(locals.identity));
};
export const PUT: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await saveTaskViewSettings(locals.identity, await readTaskInput(request)));
};

import { error, json } from '@sveltejs/kit';
import { customTasks, createCustomTask } from '$lib/server/custom-tasks';
import { readTaskInput } from '$lib/server/tasks';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, url }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await customTasks(locals.identity.household.id, url.searchParams.get('plant_id'), url.searchParams.get('site_id')));
};
export const POST: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return createCustomTask(locals.identity.household.id, await readTaskInput(request));
};

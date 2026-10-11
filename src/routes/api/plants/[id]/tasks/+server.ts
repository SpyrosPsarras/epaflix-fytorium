import { error, json } from '@sveltejs/kit';
import { plantTasks } from '$lib/server/tasks';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await plantTasks(params.id, locals.identity.household.id));
};

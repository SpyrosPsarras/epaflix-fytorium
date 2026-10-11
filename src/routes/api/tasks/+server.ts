import { error, json } from '@sveltejs/kit';
import { todayTasks } from '$lib/server/tasks';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, url }) => {
  if (!locals.identity) error(401, 'Login required');
  if (url.searchParams.has('view') && url.searchParams.get('view') !== 'today') error(422, 'Choose view=today.');
  return json(await todayTasks(locals.identity.household.id));
};

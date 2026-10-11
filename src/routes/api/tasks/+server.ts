import { error, json } from '@sveltejs/kit';
import { listedTasks } from '$lib/server/tasks';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, url }) => {
  if (!locals.identity) error(401, 'Login required');
  const view = url.searchParams.get('view') ?? 'today';
  if (view !== 'today' && view !== 'upcoming') error(422, 'Choose view=today or view=upcoming.');
  return json(await listedTasks(locals.identity, view));
};

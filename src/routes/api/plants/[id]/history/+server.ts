import { error, json } from '@sveltejs/kit';
import { history } from '$lib/server/tasks';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await history(params.id, locals.identity));
};

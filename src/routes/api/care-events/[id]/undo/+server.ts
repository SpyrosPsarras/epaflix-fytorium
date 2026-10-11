import { error, json } from '@sveltejs/kit';
import { undo } from '$lib/server/tasks';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await undo(locals.identity, params.id));
};

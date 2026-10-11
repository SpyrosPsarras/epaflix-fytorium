import { error, json } from '@sveltejs/kit';
import { undoBatch } from '$lib/server/tasks';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, request, params }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await undoBatch(locals.identity, params.id));
};

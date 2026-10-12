import { error } from '@sveltejs/kit';
import { repot } from '$lib/server/tasks';
import { readJsonObject } from '$lib/server/json';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return repot(locals.identity, params.id, await readJsonObject(request));
};

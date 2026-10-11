import { error, json } from '@sveltejs/kit';
import { completeAll, readTaskInput } from '$lib/server/tasks';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const input = await readTaskInput(request);
  if (typeof input.request_id !== 'string') error(422, 'Send a UUID request_id.');
  return json(await completeAll(locals.identity, params.id, input.request_id));
};

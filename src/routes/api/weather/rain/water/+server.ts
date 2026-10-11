import { error, json } from '@sveltejs/kit';
import { completeRain } from '$lib/server/tasks';
import { checkOrigin } from '$lib/server/sites';
import { readJsonObject } from '$lib/server/json';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const key = (await readJsonObject(request)).request_id;
  if (typeof key !== 'string') error(422, 'Send a UUID request_id.');
  return json(await completeRain(locals.identity, key));
};

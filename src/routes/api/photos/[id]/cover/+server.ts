import { error, json } from '@sveltejs/kit';
import { setCover } from '$lib/server/photos';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const PUT: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await setCover(params.id, locals.identity.household.id));
};

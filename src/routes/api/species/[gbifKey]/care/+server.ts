import { error, json } from '@sveltejs/kit';
import { speciesCare } from '$lib/server/care/catalogue';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await speciesCare(params.gbifKey, locals.identity.household.id));
};

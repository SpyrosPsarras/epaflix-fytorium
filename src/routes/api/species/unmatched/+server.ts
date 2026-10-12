import { error, json } from '@sveltejs/kit';
import { unmatchedSpecies } from '$lib/server/care/catalogue';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await unmatchedSpecies(locals.identity.household.id));
};

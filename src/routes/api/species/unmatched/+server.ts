import { error, json } from '@sveltejs/kit';
import { unmatchedSpecies } from '$lib/server/care/catalogue';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  if (locals.identity.member.role !== 'owner') error(403, 'Unmatched species require an owner');
  return json(await unmatchedSpecies(locals.identity.household.id));
};

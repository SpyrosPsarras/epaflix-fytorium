import { error } from '@sveltejs/kit';
import { unmatchedSpecies } from '$lib/server/care/catalogue';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  if (locals.identity.member.role !== 'owner') error(403, 'Unmatched species require an owner');
  return { species: await unmatchedSpecies(locals.identity.household.id) };
};

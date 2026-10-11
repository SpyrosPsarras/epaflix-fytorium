import { redirect } from '@sveltejs/kit';
import { getSpecies } from '$lib/server/names';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, params }) => {
  if (!locals.identity) redirect(303, '/auth/login');
  return { species: await getSpecies(params.gbifKey), canEdit: locals.identity.member.role === 'owner' };
};

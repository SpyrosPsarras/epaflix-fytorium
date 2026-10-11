import { error, redirect } from '@sveltejs/kit';
import { speciesCare } from '$lib/server/care/catalogue';
import { getSpecies } from '$lib/server/names';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, params }) => {
  if (!locals.identity) redirect(303, '/auth/login');
  if (!params.gbifKey) error(404, 'Species not found');
  const [care, species] = await Promise.all([
    speciesCare(params.gbifKey, locals.identity.household.id),
    getSpecies(params.gbifKey),
  ]);
  return { care, species, canEditCare: locals.identity.member.role !== 'sitter', canEdit: locals.identity.member.role === 'owner' };
};

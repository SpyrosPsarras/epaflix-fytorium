import { error, redirect } from '@sveltejs/kit';
import { speciesCare } from '$lib/server/care/catalogue';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, params }) => {
  if (!locals.identity) redirect(303, '/auth/login');
  if (!params.gbifKey) error(404, 'Species not found');
  return { care: await speciesCare(params.gbifKey, locals.identity.household.id), canEdit: locals.identity.member.role !== 'sitter' };
};

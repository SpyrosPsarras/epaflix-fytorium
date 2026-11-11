import { requireOwner, staging } from '$lib/server/planta/import';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals }) => {
  const identity = requireOwner(locals.identity);
  return { rows: await staging(identity.household.id, identity.language) };
};

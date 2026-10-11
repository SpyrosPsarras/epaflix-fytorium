import { requireOwner, staging } from '$lib/server/planta/import';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals }) => ({ rows: await staging(requireOwner(locals.identity).household.id) });

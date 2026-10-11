import type { PageServerLoad } from './$types';
export const load: PageServerLoad = ({ locals }) => ({ identity: locals.identity });

import { json } from '@sveltejs/kit';
import { requireOwner } from '$lib/server/planta/import';
import { importSuggestion } from '$lib/server/planta/suggestion';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async ({ locals, params, request }) => {
  const identity = requireOwner(locals.identity);
  checkOrigin(request);
  return json(await importSuggestion(params.id, identity.household.id), { headers: { 'cache-control': 'no-store' } });
};

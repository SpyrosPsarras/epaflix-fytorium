import { json } from '@sveltejs/kit';
import { reviewImport, requireOwner } from '$lib/server/planta/import';
import { readJsonObject } from '$lib/server/json';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const PUT: RequestHandler = async ({ locals, params, request }) => {
  const identity = requireOwner(locals.identity);
  checkOrigin(request);
  return json(await reviewImport(params.id, identity.household.id, await readJsonObject(request)));
};

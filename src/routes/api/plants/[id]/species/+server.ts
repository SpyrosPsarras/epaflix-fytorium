import { error, json } from '@sveltejs/kit';
import { changeSpecies, readLifecycleInput } from '$lib/server/plant-lifecycle';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await changeSpecies(params.id, locals.identity, await readLifecycleInput(request)));
};

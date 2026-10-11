import { error, json } from '@sveltejs/kit';
import { speciesChanges } from '$lib/server/plant-lifecycle';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await speciesChanges(params.id, locals.identity.household.id));
};

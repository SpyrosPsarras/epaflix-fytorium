import { error, json } from '@sveltejs/kit';
import { getSpecies } from '$lib/server/names';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await getSpecies(params.gbifKey));
};

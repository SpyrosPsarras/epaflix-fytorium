import { error, json } from '@sveltejs/kit';
import { getSpecies, refreshNames } from '$lib/server/names';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await getSpecies(params.gbifKey);
  await refreshNames(params.gbifKey);
  return json({ message: 'Names queued for refresh.' }, { status: 202 });
};

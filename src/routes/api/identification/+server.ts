import { error, json } from '@sveltejs/kit';
import { identificationQuota, identifyPlant } from '$lib/server/identification';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await identificationQuota());
};
export const POST: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await identifyPlant(request, locals.identity.household.id));
};

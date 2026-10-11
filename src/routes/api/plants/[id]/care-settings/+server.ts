import { error, json } from '@sveltejs/kit';
import { plantCareSettings, readSettings, savePlantCareSettings } from '$lib/server/care-settings';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await plantCareSettings(params.id, locals.identity.household.id));
};
export const PUT: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await savePlantCareSettings(params.id, locals.identity.household.id, await readSettings(request)));
};

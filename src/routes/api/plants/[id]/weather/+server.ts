import { json } from '@sveltejs/kit';
import { weatherReport } from '$lib/server/weather/cache';
import { getPlant } from '$lib/server/plants';
import { requirePermission } from '$lib/server/policy';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  const identity = requirePermission(locals.identity, 'edit');
  await getPlant(params.id, identity.household.id);
  return json(await weatherReport(identity.household.id, params.id));
};

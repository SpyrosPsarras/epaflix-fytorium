import { error, json } from '@sveltejs/kit';
import { outdoorMonths } from '$lib/server/weather/outdoor-months';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await outdoorMonths(params.gbifKey, locals.identity.household.id));
};

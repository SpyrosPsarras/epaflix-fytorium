import { error, json } from '@sveltejs/kit';
import { weatherReport } from '$lib/server/weather/cache';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await weatherReport(locals.identity.household.id));
};

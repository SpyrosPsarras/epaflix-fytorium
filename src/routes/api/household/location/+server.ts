import { error, json } from '@sveltejs/kit';
import { checkOrigin } from '$lib/server/sites';
import { householdLocation, readWeatherJson, setLocation } from '$lib/server/weather/location';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await householdLocation(locals.identity.household.id));
};
export const PUT: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  if (locals.identity.member.role !== 'owner') error(403, 'Only the owner can set household location.');
  checkOrigin(request);
  return json(await setLocation(locals.identity.household.id, (await readWeatherJson(request)).location));
};

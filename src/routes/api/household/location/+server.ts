import { error, json } from '@sveltejs/kit';
import { checkOrigin } from '$lib/server/sites';
import { householdLocation, setLocation } from '$lib/server/weather/location';
import { readJsonObject } from '$lib/server/json';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await householdLocation(locals.identity.household.id));
};
export const PUT: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await setLocation(locals.identity.household.id, (await readJsonObject(request)).location));
};

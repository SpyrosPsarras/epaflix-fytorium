import { error, json } from '@sveltejs/kit';
import { getPlant, plantFields, readPlant, writeError } from '$lib/server/plants';
import { db } from '$lib/server/db';
import { now } from '$lib/server/clock';
import { checkOrigin } from '$lib/server/sites';
import { deletePlant } from '$lib/server/plant-lifecycle';
import { readJsonObject } from '$lib/server/json';
import type { RequestHandler } from './$types';

export const PUT: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const household = locals.identity.household.id;
  const plant = await getPlant(params.id, household, locals.identity.language);
  const input = await readPlant(request, household, plant.status === 'graveyard');
  if (input instanceof Response) return input;
  const assignments = plantFields.map((field, index) => field + ' = $' + (index + 3));
  try {
    await db().query('UPDATE plants SET ' + assignments.join(', ') + ', attributes_as_of = $' + (plantFields.length + 3) + ' WHERE id = $1 AND household_id = $2',
      [params.id, household, ...plantFields.map(field => input[field]), now().toISOString().slice(0, 10)]);
  } catch (cause) {
    const failure = writeError(cause);
    if (failure) return failure;
    throw cause;
  }
  return json(await getPlant(params.id, household, locals.identity.language));
};

export const DELETE: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await deletePlant(params.id, locals.identity.household.id, await readJsonObject(request), locals.identity.language);
  return new Response(null, { status: 204 });
};

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  const plant = await getPlant(params.id, locals.identity.household.id, locals.identity.language);
  return json(plant);
};

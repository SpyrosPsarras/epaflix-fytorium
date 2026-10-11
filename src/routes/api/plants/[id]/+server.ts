import { error, json } from '@sveltejs/kit';
import { getPlant, plantFields, readPlant, writeError } from '$lib/server/plants';
import { db } from '$lib/server/db';
import { now } from '$lib/server/clock';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const PUT: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const household = locals.identity.household.id;
  await getPlant(params.id, household);
  const input = await readPlant(request, household);
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
  return json(await getPlant(params.id, household));
};

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await getPlant(params.id, locals.identity.household.id));
};

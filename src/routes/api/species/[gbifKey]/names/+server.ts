import { error, json } from '@sveltejs/kit';
import { readJsonObject } from '$lib/server/json';
import { db } from '$lib/server/db';
import { getSpecies } from '$lib/server/names';
import { nameValidationError } from '$lib/names';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await getSpecies(params.gbifKey);
  const input = await readJsonObject(request);
  const invalid = nameValidationError(input.name, input.language);
  if (invalid) error(422, invalid);
  const result = await db().query(
    "INSERT INTO species_names (gbif_key, name, language, source) VALUES ($1, $2, $3, 'owner') ON CONFLICT (gbif_key, normalised, language, source) DO UPDATE SET name = EXCLUDED.name RETURNING id, name, language, source",
    [params.gbifKey, (input.name as string).trim(), input.language],
  );
  return json(result.rows[0], { status: 201 });
};

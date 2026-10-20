import { error, json } from '@sveltejs/kit';
import { db, withTransaction } from '$lib/server/db';
import { now } from '$lib/server/clock';
import { plantFields, plantSelectFor, readPlant, getPlant, writeError } from '$lib/server/plants';
import { chosenSpecies, saveSpecies } from '$lib/server/species';
import { kickNameFetch } from '$lib/server/names';
import { visiblePlantSql } from '$lib/server/policy';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, url }) => {
  if (!locals.identity) error(401, 'Login required');
  const status = url.searchParams.get('status') ?? 'active';
  if (locals.identity.member.role === 'sitter') {
    if (status !== 'active' || url.searchParams.get('count') === 'true' || url.searchParams.has('site_id')) error(403, 'Sitters can only list shared active plants.');
    const result = await db().query(plantSelectFor(locals.identity.language) + ` WHERE p.household_id = $1 AND ${visiblePlantSql('$2')} ORDER BY display_name, p.id`, [locals.identity.household.id, locals.identity.member.id]);
    return json(result.rows);
  }
  if (!['active', 'graveyard'].includes(status)) error(422, 'Choose active or graveyard status.');
  if (url.searchParams.get('count') === 'true') {
    const result = await db().query('SELECT count(*)::integer AS count FROM plants WHERE household_id = $1 AND ($2::text IS NULL OR site_id::text = $2) AND status = $3', [locals.identity.household.id, url.searchParams.get('site_id'), status]);
    return json(result.rows[0]);
  }
  const result = await db().query(plantSelectFor(locals.identity.language) + ' WHERE p.household_id = $1 AND ($2::text IS NULL OR p.site_id::text = $2) AND p.status = $3 ORDER BY display_name, p.id', [locals.identity.household.id, url.searchParams.get('site_id'), status]);
  return json(result.rows);
};

export const POST: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const input = await readPlant(request, locals.identity.household.id);
  if (input instanceof Response) return input;
  const household = locals.identity.household.id;
  const species = chosenSpecies(input.search_id, input.gbif_key, household, input.confirmed);
  let id: string;
  try {
    id = await withTransaction(async (client) => {
      await saveSpecies(client, species);
      const fields = ['household_id', 'gbif_key', 'attributes_as_of', 'created_at', ...plantFields];
      const values = [household, species.gbif_key, now().toISOString().slice(0, 10), now(), ...plantFields.map(field => input[field])];
      const result = await client.query('INSERT INTO plants (' + fields.join(', ') + ') VALUES (' + values.map((_, i) => '$' + (i + 1)).join(', ') + ') RETURNING id', values);
      return result.rows[0].id as string;
    });
  } catch (cause) {
    const failure = writeError(cause);
    if (failure) return failure;
    throw cause;
  }
  kickNameFetch();
  return json(await getPlant(id, household, locals.identity.language), { status: 201 });
};

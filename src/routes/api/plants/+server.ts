import { error, json } from '@sveltejs/kit';
import { db } from '$lib/server/db';
import { now } from '$lib/server/clock';
import { plantFields, plantSelect, readPlant, getPlant, writeError } from '$lib/server/plants';
import { chosenSpecies } from '$lib/server/species';
import { enqueueCareFetch } from '$lib/server/care/catalogue';
import { kickNameFetch } from '$lib/server/names';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, url }) => {
  if (!locals.identity) error(401, 'Login required');
  if (url.searchParams.get('count') === 'true') {
    const result = await db().query('SELECT count(*)::integer AS count FROM plants WHERE household_id = $1 AND ($2::text IS NULL OR site_id::text = $2)', [locals.identity.household.id, url.searchParams.get('site_id')]);
    return json(result.rows[0]);
  }
  const result = await db().query(plantSelect + ' WHERE p.household_id = $1 AND ($2::text IS NULL OR p.site_id::text = $2) ORDER BY display_name, p.id', [locals.identity.household.id, url.searchParams.get('site_id')]);
  return json(result.rows);
};

export const POST: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const input = await readPlant(request, locals.identity.household.id);
  if (input instanceof Response) return input;
  const household = locals.identity.household.id;
  const species = chosenSpecies(input.search_id, input.gbif_key, household);
  if (species.requires_confirmation && input.confirmed !== true) error(422, 'Confirm this species before adding the plant.');
  const client = await db().connect();
  let id: string;
  try {
    await client.query('BEGIN');
    await client.query(
      'INSERT INTO species (gbif_key, scientific_name, canonical_name, rank, fetched_at) VALUES ($1, $2, $3, $4, $5) ON CONFLICT (gbif_key) DO UPDATE SET scientific_name = EXCLUDED.scientific_name, canonical_name = EXCLUDED.canonical_name, rank = EXCLUDED.rank, fetched_at = EXCLUDED.fetched_at',
      [species.gbif_key, species.scientific_name, species.canonical_name, species.rank, now()],
    );
    await enqueueCareFetch(client, species.gbif_key);
    await client.query("DELETE FROM species_names WHERE gbif_key = $1 AND language = 'la' AND source = 'gbif' AND name <> $2", [species.gbif_key, species.canonical_name]);
    await client.query("INSERT INTO species_names (gbif_key, name, language, source) VALUES ($1, $2, 'la', 'gbif') ON CONFLICT DO NOTHING", [species.gbif_key, species.canonical_name]);
    await client.query("INSERT INTO species_name_fetches (gbif_key, source, retry_after) SELECT $1, source, $2 FROM unnest(ARRAY['gbif', 'wikidata', 'nortaxa']) AS source ON CONFLICT DO NOTHING", [species.gbif_key, now()]);
    const fields = ['household_id', 'gbif_key', 'attributes_as_of', 'created_at', ...plantFields];
    const values = [household, species.gbif_key, now().toISOString().slice(0, 10), now(), ...plantFields.map(field => input[field])];
    const result = await client.query('INSERT INTO plants (' + fields.join(', ') + ') VALUES (' + values.map((_, i) => '$' + (i + 1)).join(', ') + ') RETURNING id', values);
    id = result.rows[0].id;
    await client.query('COMMIT');
  } catch (cause) {
    await client.query('ROLLBACK');
    const failure = writeError(cause);
    if (failure) return failure;
    throw cause;
  } finally { client.release(); }
  kickNameFetch();
  return json(await getPlant(id, household), { status: 201 });
};

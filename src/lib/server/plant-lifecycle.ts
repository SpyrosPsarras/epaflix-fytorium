import { error } from '@sveltejs/kit';
import { db } from './db';
import { getPlant, isUuid } from './plants';
import { now, calendarDate } from './clock';
import { removePhotos } from './photo-storage';
import { chosenSpecies, saveSpecies } from './species';
import { kickNameFetch } from './names';
import type { Identity } from './auth';
import type { PlantSpeciesChange } from '$lib/plants';

export async function moveToGraveyard(id: string, household: string) {
  await getPlant(id, household);
  // Keep the first date on retries and serialize with task commands and photo uploads.
  await db().query("UPDATE plants SET status = 'graveyard', graveyard_on = $3 WHERE id = $1 AND household_id = $2 AND status = 'active'", [id, household, calendarDate(now())]);
  return getPlant(id, household);
}

export async function restorePlant(id: string, household: string, input: Record<string, unknown>) {
  if (!isUuid(id)) error(404, 'Plant not found');
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    const result = await client.query('SELECT * FROM plants WHERE id = $1 AND household_id = $2 FOR UPDATE', [id, household]);
    const plant = result.rows[0];
    if (!plant) error(404, 'Plant not found');
    if (plant.status !== 'graveyard') error(409, 'Plant is already active.');
    const siteId = input.site_id ?? plant.site_id;
    if (typeof siteId !== 'string' || !isUuid(siteId)) error(422, 'Choose a site before restoring this plant.');
    const site = await client.query('SELECT id FROM sites WHERE id = $1 AND household_id = $2 FOR KEY SHARE', [siteId, household]);
    if (!site.rowCount) error(422, 'Choose a site in your household.');
    await client.query("UPDATE plants SET status = 'active', graveyard_on = NULL, site_id = $3 WHERE id = $1 AND household_id = $2", [id, household, siteId]);
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
  return getPlant(id, household);
}

export async function deletePlant(id: string, household: string, input: Record<string, unknown>) {
  await getPlant(id, household);
  const client = await db().connect();
  let keys: string[] = [];
  try {
    await client.query('BEGIN');
    const result = await client.query("SELECT p.id, coalesce(nullif(p.nickname, ''), s.canonical_name) AS display_name FROM plants p JOIN species s ON s.gbif_key = p.gbif_key WHERE p.id = $1 AND p.household_id = $2 FOR UPDATE OF p", [id, household]);
    if (!result.rowCount) error(404, 'Plant not found');
    if (input.confirm_name !== result.rows[0].display_name) error(422, 'Type the plant name to confirm permanent deletion.');
    const photos = await client.query('SELECT original_key, thumb_key FROM photo_updates WHERE plant_id = $1 AND household_id = $2', [id, household]);
    keys = photos.rows.flatMap(photo => [photo.original_key, photo.thumb_key]);
    await client.query('DELETE FROM photo_updates WHERE plant_id = $1 AND household_id = $2', [id, household]);
    // Care events and custom intervals cascade; photo_updates deliberately has a RESTRICT FK.
    await client.query('DELETE FROM plants WHERE id = $1 AND household_id = $2', [id, household]);
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
  // Commit first so a DB failure cannot leave retained photos pointing at deleted objects.
  // A storage failure must not resurrect the plant; log keys, never SDK errors containing credentials.
  if (keys.length) {
    try { await removePhotos(keys); }
    catch { console.error('Deleted plant photo cleanup failed; remove these object keys:', JSON.stringify(keys)); }
  }
}

export async function changeSpecies(id: string, identity: Identity, input: Record<string, unknown>) {
  const household = identity.household.id;
  await getPlant(id, household);
  const species = chosenSpecies(input.search_id, input.gbif_key, household, input.confirmed);
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    const result = await client.query('SELECT p.gbif_key, s.scientific_name FROM plants p JOIN species s ON s.gbif_key = p.gbif_key WHERE p.id = $1 AND p.household_id = $2 FOR UPDATE OF p', [id, household]);
    if (!result.rowCount) error(404, 'Plant not found');
    const previous = result.rows[0];
    if (previous.gbif_key !== species.gbif_key) {
      const savedSpecies = await saveSpecies(client, species);
      const member = await client.query('SELECT display_name FROM members WHERE id = $1 AND household_id = $2', [identity.member.id, household]);
      await client.query('INSERT INTO plant_species_changes (plant_id, household_id, previous_gbif_key, previous_name, next_gbif_key, next_name, changed_at, by_member, by_name) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)', [id, household, previous.gbif_key, previous.scientific_name, species.gbif_key, savedSpecies.scientific_name, now(), identity.member.id, member.rows[0].display_name]);
      await client.query('UPDATE plants SET gbif_key = $3 WHERE id = $1 AND household_id = $2', [id, household, species.gbif_key]);
    }
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
  kickNameFetch();
  // Reads recompute all tasks from the new species profile without rewriting care events or overrides.
  return getPlant(id, household);
}

export async function speciesChanges(id: string, household: string): Promise<PlantSpeciesChange[]> {
  await getPlant(id, household);
  const result = await db().query('SELECT id, previous_gbif_key, previous_name, next_gbif_key, next_name, changed_at, by_name FROM plant_species_changes WHERE plant_id = $1 AND household_id = $2 ORDER BY changed_at DESC, id DESC', [id, household]);
  return result.rows.map(row => ({ ...row, changed_on: calendarDate(row.changed_at) }));
}

export async function readLifecycleInput(request: Request): Promise<Record<string, unknown>> {
  let input;
  try {
    if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') throw new Error('Expected JSON');
    input = await request.json();
  } catch { error(400, 'Send a valid JSON object.'); }
  if (!input || typeof input !== 'object' || Array.isArray(input)) error(400, 'Send a valid JSON object.');
  return input;
}

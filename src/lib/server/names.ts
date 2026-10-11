import { error } from '@sveltejs/kit';
import { db } from './db';
import { now } from './clock';
import { gbifNames } from './name-sources/gbif';
import { wikidataNames } from './name-sources/wikidata';
import { nortaxaNames } from './name-sources/nortaxa';
import { nameValidationError, type SpeciesName } from '$lib/names';

export async function getSpecies(key: string) {
  if (!/^[1-9][0-9]*$/.test(key) || Number(key) > 2147483647) error(404, 'Species not found');
  const result = await db().query<{ gbif_key: number; scientific_name: string; canonical_name: string; rank: string; name_fetch_needed: boolean; name_fetch_failed: boolean }>(`
    SELECT gbif_key, scientific_name, canonical_name, rank,
      EXISTS (SELECT 1 FROM species_name_fetches f WHERE f.gbif_key = s.gbif_key AND NOT completed AND failures < 8) AS name_fetch_needed,
      EXISTS (SELECT 1 FROM species_name_fetches f WHERE f.gbif_key = s.gbif_key AND failures = 8) AS name_fetch_failed
    FROM species s WHERE gbif_key = $1`, [key]);
  if (!result.rowCount) error(404, 'Species not found');
  const names = await db().query<SpeciesName>('SELECT id, name, language, source FROM species_names WHERE gbif_key = $1 ORDER BY language, name, source', [key]);
  return { ...result.rows[0], names: names.rows };
}

let working = false;
let started = false;
let stopping = false;
export function startNameWorker() {
  if (started) return;
  started = true;
  const timer = setInterval(kickNameFetch, 5000);
  timer.unref();
  process.once('sveltekit:shutdown', () => { stopping = true; clearInterval(timer); });
  kickNameFetch();
}
export function kickNameFetch() {
  void fillNames().catch(() => console.error('Name fetch queue failed; pending sources will retry.'));
}
export async function refreshNames(key: string) {
  await db().query('UPDATE species_name_fetches SET completed = false, failures = 0, retry_after = $2, version = version + 1 WHERE gbif_key = $1', [key, now()]);
  kickNameFetch();
}
async function fillNames() {
  if (working || stopping) return;
  working = true;
  try {
    for (let batch = 0; batch < 30 && !stopping; batch++) {
      const time = now();
      // A lease survives process failure; the version prevents a stale fetch from undoing a refresh.
      const pending = await db().query(
        `UPDATE species_name_fetches f SET retry_after = $2, version = f.version + 1
         FROM species s WHERE s.gbif_key = f.gbif_key AND (f.gbif_key, f.source) =
           (SELECT gbif_key, source FROM species_name_fetches WHERE NOT completed AND failures < 8 AND retry_after <= $1
            ORDER BY retry_after, gbif_key, source LIMIT 1 FOR UPDATE SKIP LOCKED)
         RETURNING f.gbif_key, f.source, f.failures, f.version, s.canonical_name`,
        [time, new Date(time.getTime() + 30000)],
      );
      const species = pending.rows[0];
      if (!species) return;
      const source = [gbifNames, wikidataNames, nortaxaNames].find(item => item.source === species.source)!;
      try {
        const names = await source.fetchNames(species);
        if (names.some(row => nameValidationError(row.name, row.language))) throw new Error('Invalid source name');
        const client = await db().connect();
        try {
          await client.query('BEGIN');
          const current = await client.query('SELECT 1 FROM species_name_fetches WHERE gbif_key = $1 AND source = $2 AND version = $3 FOR UPDATE', [species.gbif_key, source.source, species.version]);
          if (!current.rowCount) { await client.query('ROLLBACK'); continue; }
          await client.query("DELETE FROM species_names WHERE gbif_key = $1 AND source = $2 AND language <> 'la'", [species.gbif_key, source.source]);
          for (const row of names) await client.query('INSERT INTO species_names (gbif_key, name, language, source) VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING', [species.gbif_key, row.name.trim(), row.language, source.source]);
          await client.query('UPDATE species_name_fetches SET completed = true WHERE gbif_key = $1 AND source = $2 AND version = $3', [species.gbif_key, source.source, species.version]);
          await client.query('COMMIT');
        } catch (cause) { await client.query('ROLLBACK'); throw cause; }
        finally { client.release(); }
      } catch {
        const failures = species.failures + 1;
        const delay = Math.min(60000 * 2 ** (failures - 1), 86400000);
        await db().query('UPDATE species_name_fetches SET failures = $4, retry_after = $5 WHERE gbif_key = $1 AND source = $2 AND version = $3', [species.gbif_key, source.source, species.version, failures, new Date(now().getTime() + delay)]);
        console.error('Name fetch failed for ' + source.source + ' species ' + species.gbif_key + (failures < 8 ? '; will retry.' : '; stopped after 8 failures. Refresh names to retry.'));
      }
    }
  } finally { working = false; }
}

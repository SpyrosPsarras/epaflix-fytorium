import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { error, isHttpError } from '@sveltejs/kit';
import { db } from './db';
import { now } from './clock';
import { matchSpecies } from './gbif';
import { enqueueCareFetch } from './care/catalogue';
import type { Candidate } from '$lib/plants';

// A five-minute server record avoids a second GBIF call and never trusts client-sent taxonomy.
// Records are household-scoped and bounded; after a restart or expiry the owner searches again.
const searches = new Map<string, { household: string; expires: number; candidates: Candidate[] }>();
export async function searchSpecies(query: string, household: string) {
  query = query.trim();
  const names = await db().query(`
    WITH input AS (SELECT normalise_species_name($1) AS query, normalise_species_name($2) AS prefix), hits AS (
      SELECT DISTINCT ON (n.gbif_key) s.gbif_key, s.scientific_name, s.canonical_name, s.rank,
        n.name AS matched_name, n.language AS matched_language,
        NOT (s.rank = 'SPECIES' AND n.normalised = input.query AND
          (SELECT count(DISTINCT gbif_key) FROM species_names WHERE normalised = input.query) = 1) AS requires_confirmation,
        similarity(n.normalised, input.query) + CASE WHEN starts_with(n.normalised, input.query) THEN 0.2 ELSE 0 END AS score
      FROM species_names n JOIN species s USING (gbif_key) CROSS JOIN input
      WHERE n.normalised % input.query OR n.normalised LIKE input.prefix
      ORDER BY n.gbif_key, score DESC, n.name, n.language, n.source
    ) SELECT * FROM hits ORDER BY score DESC, scientific_name LIMIT 20`, [query, query.replace(/[\\%_]/g, '\\$&') + '%']);
  const local = await db().query(`
    SELECT gbif_key, scientific_name, canonical_name, rank FROM species
    WHERE position(lower($1) in lower(scientific_name)) > 0 OR position(lower($1) in lower(canonical_name)) > 0
    ORDER BY (rank = 'SPECIES' AND (lower(scientific_name) = lower($1) OR lower(canonical_name) = lower($1))) DESC, scientific_name
    LIMIT 20`, [query]);
  const exactName = (row: Candidate) => [row.scientific_name, row.canonical_name].some(name => name.toLowerCase() === query.toLowerCase());
  const nameCandidates: Candidate[] = names.rows.map(({ score: _score, ...row }) => ({ ...row, match_type: null, confidence: null }));
  const scientificCandidates: Candidate[] = local.rows.map(row => ({
    ...row, match_type: null, confidence: null, requires_confirmation: !exactName(row) || row.rank !== 'SPECIES',
  }));
  // Scientific exact matches take priority, even when a vernacular neighbour also matches.
  const merged = new Map(nameCandidates.map(row => [row.gbif_key, row]));
  for (const row of scientificCandidates) {
    if (!merged.has(row.gbif_key) || !row.requires_confirmation) merged.set(row.gbif_key, { ...merged.get(row.gbif_key), ...row });
  }
  const scientificPriority = (row: Candidate) => !row.requires_confirmation && exactName(row) ? 1 : 0;
  let candidates = [...merged.values()].sort((a, b) => scientificPriority(b) - scientificPriority(a));
  if (!candidates.some(row => !row.requires_confirmation)) {
    try {
      const matches = await matchSpecies(query);
      // GBIF can confirm scientific names, but fuzzy or ambiguous vernacular hits still need confirmation.
      candidates = [...matches.map(match => {
        const named = nameCandidates.find(row => row.gbif_key === match.gbif_key);
        return named ? { ...named, ...match, requires_confirmation: (named.matched_language !== 'la' && named.requires_confirmation) || match.requires_confirmation } : match;
      }), ...candidates.filter(local => !matches.some(match => match.gbif_key === local.gbif_key))];
    } catch (cause) {
      if (!candidates.length || !isHttpError(cause) || ![422, 503].includes(cause.status)) throw cause;
    }
  }
  if (!candidates.length) error(422, 'No species-rank plant matched that name. Try another name.');
  return recordSpeciesSearch(candidates, household);
}

export function recordSpeciesSearch(candidates: Candidate[], household: string) {
  const time = now().getTime();
  for (const [id, record] of searches) if (record.expires <= time) searches.delete(id);
  if (searches.size >= 1000) searches.delete(searches.keys().next().value!);
  const search_id = randomUUID();
  searches.set(search_id, { household, expires: time + 300000, candidates });
  return { search_id, candidates };
}
export function chosenSpecies(searchId: unknown, key: unknown, household: string, confirmed: unknown): Candidate {
  const record = typeof searchId === 'string' ? searches.get(searchId) : undefined;
  if (!record || record.household !== household || record.expires <= now().getTime()) error(422, 'Search again before choosing this species.');
  const candidate = record.candidates.find(item => item.gbif_key === key);
  if (!candidate) error(422, 'Choose a species from the search results.');
  if (candidate.requires_confirmation && confirmed !== true) error(422, 'Confirm this species before saving the plant.');
  return candidate;
}

export async function saveSpecies(client: PoolClient, species: Candidate, preserveTaxonomy = species.source === 'identification') {
  await client.query(
    'INSERT INTO species (gbif_key, scientific_name, canonical_name, rank, fetched_at) VALUES ($1, $2, $3, $4, $5) ON CONFLICT (gbif_key) ' +
    (preserveTaxonomy ? 'DO NOTHING' : 'DO UPDATE SET scientific_name = EXCLUDED.scientific_name, canonical_name = EXCLUDED.canonical_name, rank = EXCLUDED.rank, fetched_at = EXCLUDED.fetched_at'),
    [species.gbif_key, species.scientific_name, species.canonical_name, species.rank, now()],
  );
  await enqueueCareFetch(client, species.gbif_key);
  // Identification and imports keep existing taxonomy, so index the stored canonical name.
  const stored = await client.query<{ scientific_name: string; canonical_name: string }>('SELECT scientific_name, canonical_name FROM species WHERE gbif_key = $1 FOR UPDATE', [species.gbif_key]);
  const canonicalName = stored.rows[0].canonical_name;
  await client.query("DELETE FROM species_names WHERE gbif_key = $1 AND language = 'la' AND source = 'gbif' AND name <> $2", [species.gbif_key, canonicalName]);
  await client.query("INSERT INTO species_names (gbif_key, name, language, source) VALUES ($1, $2, 'la', 'gbif') ON CONFLICT DO NOTHING", [species.gbif_key, canonicalName]);
  await client.query("INSERT INTO species_name_fetches (gbif_key, source, retry_after) SELECT $1, source, $2 FROM unnest(ARRAY['gbif', 'wikidata', 'nortaxa']) AS source ON CONFLICT DO NOTHING", [species.gbif_key, now()]);
  return stored.rows[0];
}

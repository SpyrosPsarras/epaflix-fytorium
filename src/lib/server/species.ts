import { randomUUID } from 'node:crypto';
import { error, isHttpError } from '@sveltejs/kit';
import { db } from './db';
import { now } from './clock';
import { matchSpecies } from './gbif';
import type { Candidate } from '$lib/plants';

// A five-minute server record avoids a second GBIF call and never trusts client-sent taxonomy.
// Records are household-scoped and bounded; after a restart or expiry the owner searches again.
const searches = new Map<string, { household: string; expires: number; candidates: Candidate[] }>();
export async function searchSpecies(query: string, household: string) {
  query = query.trim();
  const local = await db().query('SELECT gbif_key, scientific_name, canonical_name, rank FROM species WHERE position(lower($1) in lower(scientific_name)) > 0 OR position(lower($1) in lower(canonical_name)) > 0 ORDER BY scientific_name LIMIT 20', [query]);
  const exactName = (row: Candidate) => [row.scientific_name, row.canonical_name].some(name => name.toLowerCase() === query.toLowerCase());
  let candidates: Candidate[] = local.rows.map(row => ({
    ...row, match_type: null, confidence: null, requires_confirmation: !exactName(row) || row.rank !== 'SPECIES',
  }));
  if (!candidates.some(exactName)) {
    try {
      const matches = await matchSpecies(query);
      // Keep GBIF's actual match metadata when the same key is also stored locally.
      candidates = [...matches, ...candidates.filter(local => !matches.some(match => match.gbif_key === local.gbif_key))];
    } catch (cause) {
      if (!candidates.length || !isHttpError(cause) || ![422, 503].includes(cause.status)) throw cause;
    }
  }
  if (!candidates.length) error(422, 'No species-rank plant matched that name. Try another name.');
  const time = now().getTime();
  for (const [id, record] of searches) if (record.expires <= time) searches.delete(id);
  if (searches.size >= 1000) searches.delete(searches.keys().next().value!);
  const search_id = randomUUID();
  searches.set(search_id, { household, expires: time + 300000, candidates });
  return { search_id, candidates };
}
export function chosenSpecies(searchId: unknown, key: unknown, household: string): Candidate {
  const record = typeof searchId === 'string' ? searches.get(searchId) : undefined;
  if (!record || record.household !== household || record.expires <= now().getTime()) error(422, 'Search again before adding this plant.');
  const candidate = record.candidates.find(item => item.gbif_key === key);
  if (!candidate) error(422, 'Choose a species from the search results.');
  return candidate;
}

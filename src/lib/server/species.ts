import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { error, isHttpError } from '@sveltejs/kit';
import { db } from './db';
import { now } from './clock';
import { acceptedSpecies, isGbifKey, matchSpecies } from './gbif';
import { remoteSpeciesSearch, type RemoteCandidate } from './species-remote';
import { rankCandidates, searchNameKey, wordsMatch } from './species-ranking';
import { pickCommonName } from './common-name';
import { enqueueCareFetch } from './care/catalogue';
import { lockUpdateAndRecomputeCommonNames } from './names';
import type { Candidate } from '$lib/plants';
import type { CommonNameLanguage } from '$lib/names';

// The bounded receipt only trusts taxonomy from the household's search results.
const searches = new Map<string, { household: string; expires: number; candidates: Candidate[] }>();

async function localSpeciesSearch(query: string, exactQuery: string, fuzzy = false): Promise<(RemoteCandidate & { exact: boolean })[]> {
  const result = await db().query<Pick<Candidate, 'gbif_key' | 'scientific_name' | 'canonical_name' | 'rank'> & {
    name: string; language: Candidate['matched_language'] | null; literal_prefix: boolean;
  }>(`
    WITH input AS (SELECT normalise_species_name($1) AS query), names AS (
      SELECT gbif_key, name, language, normalised FROM species_names
      ${fuzzy ? 'WHERE normalised % (SELECT query FROM input)' : `UNION ALL
      SELECT gbif_key, scientific_name, NULL, normalise_species_name(scientific_name) FROM species
      UNION ALL SELECT gbif_key, canonical_name, NULL, normalise_species_name(canonical_name) FROM species`}
    ) SELECT s.gbif_key, s.scientific_name, s.canonical_name, s.rank, n.name, n.language,
      starts_with(n.normalised, input.query) AS literal_prefix
    FROM names n JOIN species s USING (gbif_key) CROSS JOIN input
    ORDER BY (n.language IS NULL OR n.language = 'la') DESC,
      ${fuzzy ? 'similarity(n.normalised, input.query)' : '-length(n.normalised)'} DESC,
      s.scientific_name, s.gbif_key, n.language NULLS LAST, n.name`, [query]);
  return result.rows.flatMap(({ name, language, literal_prefix, ...row }) => {
    const exact = !fuzzy && !!exactQuery && searchNameKey(name) === exactQuery;
    if (!fuzzy && !exact && !(exactQuery ? wordsMatch(query, name) : literal_prefix)) return [];
    const candidate: Candidate = { ...row, ...(language ? { matched_name: name, matched_language: language } : {}),
      match_type: null, confidence: null, requires_confirmation: row.rank !== 'SPECIES' || !exact };
    return [{ candidate, exact }];
  });
}

export async function matchPlantaSpecies(query: string): Promise<Candidate[]> {
  query = query.trim().replace(/\s+/g, ' ');
  const exactQuery = searchNameKey(query);
  const local = await localSpeciesSearch(query, exactQuery);
  let ranked = rankCandidates(local);
  const exact = ranked.filter(entry => entry.exact);
  if (exact.length === 1 && !exact[0].candidate.requires_confirmation) return ranked.map(({ candidate }) => candidate);

  const matches = await matchSpecies(query).catch(cause => {
    if (isHttpError(cause, 422) || isHttpError(cause, 503)) return [];
    throw cause;
  });
  ranked = rankCandidates(local, matches.map(candidate => ({ candidate,
    exact: !!exactQuery && [candidate.canonical_name, candidate.scientific_name].some(name => searchNameKey(name) === exactQuery),
  })));
  if (!ranked.length) ranked = rankCandidates(await localSpeciesSearch(query, exactQuery, true));
  const ambiguous = ranked.filter(entry => entry.exact).length > 1;
  return ranked.map(({ candidate }) => ({ ...candidate, requires_confirmation: candidate.requires_confirmation || ambiguous }));
}

export async function searchSpecies(query: string, household: string, language: CommonNameLanguage) {
  query = query.trim().replace(/\s+/g, ' ');
  const exactQuery = searchNameKey(query);
  const equal = (name: string) => !!exactQuery && searchNameKey(name) === exactQuery;
  const [local, remote] = await Promise.all([localSpeciesSearch(query, exactQuery), remoteSpeciesSearch(query)]);
  const entries = remote.candidates.map(({ candidate, names, gbifNames }) => {
    const exactAlias = names?.find(row => equal(row.name));
    const matched = exactAlias ?? names?.find(row => wordsMatch(query, row.name));
    return { candidate: { ...candidate, ...(matched ? { matched_name: matched.name, matched_language: matched.language } : {}),
      requires_confirmation: names ? !exactAlias : candidate.requires_confirmation },
      exact: !!exactAlias || [candidate.canonical_name, candidate.scientific_name, ...(gbifNames ?? [])].some(equal), names };
  });
  let ranked = rankCandidates(local, entries);
  if (!ranked.length) ranked = rankCandidates(await localSpeciesSearch(query, exactQuery, true));
  if (!ranked.length) {
    if (remote.unavailable) error(503, 'Species search is unavailable. Try again in a minute.');
    error(422, 'No plant species matched that name. Try another name.');
  }
  const ambiguous = ranked.filter(entry => entry.exact).length > 1;
  const namesByKey = await speciesCommonNames(ranked.map(({ candidate }) => candidate.gbif_key), language);
  const candidates = ranked.map(({ candidate, names }) => ({ ...candidate,
    requires_confirmation: candidate.requires_confirmation || ambiguous,
    common_name: namesByKey.has(candidate.gbif_key) ? namesByKey.get(candidate.gbif_key)! : names
      ? pickCommonName(candidate.canonical_name, names.filter(row => row.language === language).map(row => ({ ...row, source: 'wikidata' }))) : null,
  }));
  return recordSpeciesSearch(candidates, household);
}

export async function speciesCommonNames(keys: number[], language: CommonNameLanguage) {
  const result = await db().query<{ gbif_key: number; common_name: string | null }>(
    'SELECT gbif_key, species_common_name(gbif_key, $2) AS common_name FROM species WHERE gbif_key = ANY($1::integer[])', [keys, language]);
  return new Map(result.rows.map(row => [row.gbif_key, row.common_name]));
}

export async function withCommonNames(candidates: { gbif_key: number }[], language: CommonNameLanguage) {
  const names = await speciesCommonNames(candidates.map(candidate => candidate.gbif_key), language);
  return <T extends { gbif_key: number }>(candidate: T) => ({ ...candidate, common_name: names.get(candidate.gbif_key) ?? null });
}

export function recordSpeciesSearch(candidates: Candidate[], household: string) {
  const time = now().getTime();
  for (const [id, record] of searches) if (record.expires <= time) searches.delete(id);
  if (searches.size >= 1000) searches.delete(searches.keys().next().value!);
  const search_id = randomUUID();
  searches.set(search_id, { household, expires: time + 300000, candidates });
  return { search_id, candidates };
}
export async function chosenSpecies(searchId: unknown, key: unknown, household: string, confirmed: unknown, { requireReceipt = false } = {}): Promise<Candidate> {
  const record = typeof searchId === 'string' ? searches.get(searchId) : undefined;
  if (record && record.expires > now().getTime()) {
    if (record.household !== household) error(422, 'Search again before choosing this species.');
    const candidate = record.candidates.find(item => item.gbif_key === key);
    if (!candidate) error(422, 'Choose a species from the search results.');
    if (candidate.requires_confirmation && confirmed !== true) error(422, 'Confirm this species before saving the plant.');
    return candidate;
  }
  if (!requireReceipt && confirmed === true && isGbifKey(key)) {
    const stored = await db().query<Pick<Candidate, 'gbif_key' | 'scientific_name' | 'canonical_name' | 'rank'>>(
      'SELECT gbif_key, scientific_name, canonical_name, rank FROM species WHERE gbif_key = $1', [key]);
    if (stored.rows[0]) return { ...stored.rows[0], confidence: null, match_type: null, requires_confirmation: true };
    const candidate = await acceptedSpecies(key).catch(() => null);
    if (candidate) return candidate;
  }
  error(422, 'Search again before choosing this species.');
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
  await lockUpdateAndRecomputeCommonNames(client, species.gbif_key);
  return stored.rows[0];
}

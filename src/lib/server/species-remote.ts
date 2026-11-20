import { isHttpError } from '@sveltejs/kit';
import type { Candidate } from '$lib/plants';
import { commonNameLanguages } from '$lib/names';
import { now } from './clock';
import { db } from './db';
import { acceptedSpecies, isGbifKey, matchSpecies } from './gbif';
import { wikidataJson, wikidataNamesFrom } from './name-sources/wikidata';
import type { FetchedName } from './name-sources/shared';
import { isPlaceholder } from './species-ranking';

export type RemoteCandidate = { candidate: Candidate; names?: FetchedName[]; gbifNames?: string[] };

async function wikidataSpecies(query: string, deadline: AbortSignal) {
  const response = await wikidataJson({ action: 'query', list: 'search', srsearch: query + ' haswbstatement:P846', srnamespace: '0', srlimit: '10' }, deadline);
  if (!Array.isArray(response.query?.search)) throw new Error('Invalid Wikidata search');
  const ids: string[] = response.query.search.slice(0, 10).map((row: { title?: unknown }) => row?.title)
    .filter((id: unknown) => typeof id === 'string' && /^Q[1-9]\d*$/.test(id));
  if (!ids.length) return [];
  const payload = await wikidataJson({ action: 'wbgetentities', ids: ids.join('|'), props: 'claims|labels|aliases', languages: commonNameLanguages.join('|') }, deadline);
  if (!payload.entities || typeof payload.entities !== 'object') throw new Error('Invalid Wikidata entities');
  const lookups = new Map<number, Promise<RemoteCandidate | null>>();
  const entries: { lookup: Promise<RemoteCandidate | null>; names: FetchedName[] }[] = [];
  for (const id of ids) {
    const item = payload.entities[id];
    if (!Array.isArray(item?.claims?.P846)) continue;
    const names = wikidataNamesFrom(item);
    for (const claim of item.claims.P846) {
      const value = claim?.mainsnak?.datavalue?.value;
      const key = typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : null;
      if (!isGbifKey(key)) continue;
      let lookup = lookups.get(key);
      if (!lookup) {
        if (lookups.size >= 5) break;
        let gbifNames: string[] | undefined;
        lookup = acceptedSpecies(key, { deadline, synonyms: true, onSynonymNames: names => { gbifNames = names; } })
          .then(candidate => candidate && { candidate, gbifNames });
        lookups.set(key, lookup);
      }
      entries.push({ lookup, names });
    }
  }
  return Promise.allSettled(entries.map(async ({ lookup, names }) => {
    const result = await lookup;
    return result && { ...result, names };
  }));
}

export async function remoteSpeciesSearch(query: string) {
  const key = query.toLowerCase();
  const cached = await db().query<{ candidates: RemoteCandidate[] }>('SELECT candidates FROM species_search_cache WHERE query = $1 AND expires_at > $2', [key, now()]);
  if (cached.rows.length) return { unavailable: false, candidates: cached.rows[0].candidates };

  const deadline = AbortSignal.timeout(8000);
  let gbifPartial = false;
  const gbifNames = new Map<number, string[]>();
  const [wikidata, gbif] = await Promise.allSettled([
    wikidataSpecies(query, deadline),
    matchSpecies(query, { deadline, alternativesOnNoMatch: true, onPartialFailure: () => { gbifPartial = true; },
      onCandidateNames: (key, names) => gbifNames.set(key, [...(gbifNames.get(key) ?? []), ...names]),
    }).catch(cause => {
      if (isHttpError(cause, 422)) return [];
      throw cause;
    }),
  ]);
  const wikiLookups = wikidata.status === 'fulfilled' ? wikidata.value : [];
  const wikiFailed = wikidata.status === 'rejected' || wikiLookups.some(row => row.status === 'rejected');
  const candidates: RemoteCandidate[] = wikiLookups.flatMap(row => row.status === 'fulfilled' && row.value ? [row.value] : []);
  if (gbif.status === 'fulfilled') candidates.push(...gbif.value.map(candidate => ({ candidate, gbifNames: gbifNames.get(candidate.gbif_key) })));
  const filtered = candidates.filter(({ candidate }) => !isPlaceholder(candidate));
  if (!wikiFailed && !gbifPartial && gbif.status === 'fulfilled') {
    const time = now();
    await db().query(`INSERT INTO species_search_cache (query, candidates, expires_at) VALUES ($1, $2, $3)
      ON CONFLICT (query) DO UPDATE SET candidates = EXCLUDED.candidates, expires_at = EXCLUDED.expires_at`,
    [key, JSON.stringify(filtered), new Date(time.getTime() + (filtered.length ? 7 : 1) * 86400000)]);
    await db().query(`DELETE FROM species_search_cache WHERE query IN (
      SELECT query FROM species_search_cache WHERE expires_at <= $1 FOR UPDATE SKIP LOCKED
    )`, [time]);
  }
  return { candidates: filtered, unavailable: gbif.status === 'rejected' && wikiFailed && !wikiLookups.some(row => row.status === 'fulfilled') };
}

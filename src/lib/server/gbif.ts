import { error } from '@sveltejs/kit';
import { config } from './config';
import { sourceUrl, taxonomyFetch } from './name-sources/shared';
import type { Candidate } from '$lib/plants';

type Match = {
  usageKey: number; scientificName: string; canonicalName: string; rank: string; kingdom: string;
  matchType: string; confidence: number; acceptedUsageKey?: number; status?: string; taxonomicStatus?: string;
  acceptedScientificName?: string; acceptedCanonicalName?: string; alternatives?: Match[];
};

const ranks = ['SPECIES', 'SUBSPECIES', 'VARIETY', 'SUBVARIETY', 'FORM', 'SUBFORM', 'CULTIVAR', 'INFRASPECIFIC_NAME'];

function acceptedRank(rank: string, canonicalName: string, name: string, genus: boolean): boolean {
  return ranks.includes(rank) || (genus && rank === 'GENUS' && canonicalName === name);
}

type ReferencePhoto = { identifier: string; creator: string; license: 'CC BY 4.0' | 'CC0'; source_url: string };
export async function referencePhoto(key: number): Promise<ReferencePhoto | null> {
  const url = sourceUrl(config().gbifBaseUrl, 'occurrence/search');
  url.search = new URLSearchParams({ taxonKey: String(key), mediaType: 'StillImage', limit: '1' }).toString();
  url.searchParams.append('license', 'CC0_1_0');
  url.searchParams.append('license', 'CC_BY_4_0');
  let body;
  try {
    const response = await taxonomyFetch(url);
    if (!response.ok) throw new Error();
    body = await response.json();
    if (!Array.isArray(body?.results)) throw new Error();
  } catch { error(503, 'Reference photos are unavailable. GBIF did not respond. Try again.'); }
  const occurrence = body.results[0];
  const media = occurrence?.media?.[0];
  if (typeof media?.identifier !== 'string' || typeof media.license !== 'string') return null;
  const license = /^https?:\/\/creativecommons\.org\/licenses\/by\/4\.0\/?$/.test(media.license) ? 'CC BY 4.0'
    : /^https?:\/\/creativecommons\.org\/publicdomain\/zero\/1\.0\/?$/.test(media.license) ? 'CC0' : null;
  if (!license) return null;
  let source: URL;
  try { source = new URL(occurrence.references); }
  catch { return null; }
  if (!['https:', 'http:'].includes(source.protocol) || source.username || source.password) return null;
  const creator = [media.creator, media.rightsHolder].find(value => typeof value === 'string' && value.trim());
  return { identifier: media.identifier, creator: creator?.trim().slice(0, 500) ?? 'Unknown photographer', license, source_url: source.href };
}

export function isGbifKey(key: unknown): key is number {
  return typeof key === 'number' && Number.isInteger(key) && key > 0 && key <= 2147483647;
}

async function speciesRecord(key: number, deadline?: AbortSignal) {
  const response = await taxonomyFetch(sourceUrl(config().gbifBaseUrl, 'species/' + key), deadline);
  if (response.status === 404) return null;
  if (!response.ok) throw new Error('GBIF species unavailable');
  const species = await response.json();
  if (species?.key !== key || typeof species.kingdom !== 'string' || typeof species.rank !== 'string') throw new Error('Invalid GBIF species');
  return species;
}

export async function speciesRank(key: number): Promise<string | undefined> {
  const species = await speciesRecord(key).catch(() => null);
  if (species?.kingdom === 'Plantae' && ranks.includes(species.rank)) return species.rank;
}

export async function acceptedSpecies(key: number, { deadline, synonyms = false, onSynonymNames }: {
  deadline?: AbortSignal; synonyms?: boolean; onSynonymNames?: (names: string[]) => void;
} = {}): Promise<Candidate | null> {
  const original = await speciesRecord(key, deadline);
  let species = original;
  if (species?.kingdom !== 'Plantae' || !ranks.includes(species.rank) || species.taxonomicStatus === 'DOUBTFUL') return null;
  if (synonyms && isGbifKey(species.acceptedKey) && species.acceptedKey !== key) {
    key = species.acceptedKey;
    species = await speciesRecord(key, deadline);
  }
  if (species?.taxonomicStatus !== 'ACCEPTED' || species.kingdom !== 'Plantae' || !ranks.includes(species.rank) ||
    typeof species.scientificName !== 'string' || !species.scientificName.trim() ||
    typeof species.canonicalName !== 'string' || !species.canonicalName.trim()) return null;
  if (species !== original) {
    const names: unknown[] = [original.scientificName, original.canonicalName];
    onSynonymNames?.(names.filter((name): name is string => typeof name === 'string' && !!name.trim()));
  }
  return { gbif_key: key, scientific_name: species.scientificName, canonical_name: species.canonicalName,
    rank: species.rank, match_type: null, confidence: null, requires_confirmation: true };
}

export async function matchSpecies(name: string, { alternatives = true, alternativesOnNoMatch = false, genus = false, deadline, onPartialFailure, onCandidateNames }: {
  alternatives?: boolean; alternativesOnNoMatch?: boolean; genus?: boolean; deadline?: AbortSignal; onPartialFailure?: () => void;
  onCandidateNames?: (key: number, names: string[]) => void;
} = {}): Promise<Candidate[]> {
  const url = sourceUrl(config().gbifBaseUrl, 'species/match');
  url.search = new URLSearchParams({ name, kingdom: 'Plantae', verbose: 'true' }).toString();
  const signal = deadline ?? AbortSignal.timeout(3000);
  let match: Match;
  try {
    const response = await taxonomyFetch(url, signal);
    if (!response.ok) throw new Error('GBIF request failed');
    match = await response.json();
    if (!match || typeof match !== 'object' || typeof match.matchType !== 'string' || (match.alternatives !== undefined && !Array.isArray(match.alternatives))) throw new Error('Invalid GBIF response');
  } catch {
    error(503, 'Species search is unavailable. GBIF did not respond. Try again.');
  }
  if (match.matchType === 'NONE' && !alternativesOnNoMatch) error(422, 'No plant species matched that name. Try another name.');
  const candidates: Candidate[] = [];
  for (const [index, item] of [match, ...(alternatives ? (match.alternatives ?? []).slice(0, 5) : [])].entries()) {
    if (!item || item.kingdom !== 'Plantae' || !acceptedRank(item.rank, item.canonicalName, name, genus) || (index > 0 && item.rank !== 'SPECIES') || item.matchType === 'NONE' || item.status === 'DOUBTFUL' || item.taxonomicStatus === 'DOUBTFUL') continue;
    if (!isGbifKey(item.usageKey) || typeof item.scientificName !== 'string' || typeof item.canonicalName !== 'string') continue;
    const key = item.acceptedUsageKey ?? item.usageKey;
    const confidence = typeof item.confidence === 'number' && Number.isFinite(item.confidence) ? item.confidence : 0;
    if (!isGbifKey(key)) continue;
    const names = [item.scientificName, item.canonicalName];
    if (candidates.some(candidate => candidate.gbif_key === key)) {
      onCandidateNames?.(key, names);
      continue;
    }
    let scientificName = item.scientificName;
    let canonicalName = item.canonicalName;
    let rank = item.rank;
    if (item.acceptedUsageKey !== undefined) {
      if (typeof item.acceptedScientificName === 'string' && item.acceptedScientificName.trim() && typeof item.acceptedCanonicalName === 'string' && item.acceptedCanonicalName.trim()) {
        scientificName = item.acceptedScientificName;
        canonicalName = item.acceptedCanonicalName;
      } else {
        try {
          const accepted = await speciesRecord(key, signal);
          if (accepted?.taxonomicStatus === 'DOUBTFUL') continue;
          if (!accepted || typeof accepted.scientificName !== 'string' || !accepted.scientificName.trim() || typeof accepted.canonicalName !== 'string' || !accepted.canonicalName.trim() || accepted.kingdom !== 'Plantae' || !acceptedRank(accepted.rank, accepted.canonicalName, name, genus)) throw new Error('Invalid GBIF accepted species');
          scientificName = accepted.scientificName;
          canonicalName = accepted.canonicalName;
          rank = accepted.rank;
        } catch {
          if (index > 0) { onPartialFailure?.(); continue; }
          error(503, 'Species search is unavailable. GBIF did not return the accepted species. Try again.');
        }
      }
    }
    onCandidateNames?.(key, names);
    candidates.push({
      gbif_key: key, scientific_name: scientificName, canonical_name: canonicalName,
      rank, match_type: typeof item.matchType === 'string' ? item.matchType : 'UNKNOWN', confidence,
      requires_confirmation: index > 0 || item.matchType !== 'EXACT' || confidence < 90 || rank !== 'SPECIES',
    });
  }
  return candidates;
}

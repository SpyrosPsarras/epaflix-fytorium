import { error } from '@sveltejs/kit';
import { config } from './config';
import type { Candidate } from '$lib/plants';

type Match = {
  usageKey: number; scientificName: string; canonicalName: string; rank: string; kingdom: string;
  matchType: string; confidence: number; acceptedUsageKey?: number;
  acceptedScientificName?: string; acceptedCanonicalName?: string; alternatives?: Match[];
};

const ranks = ['SPECIES', 'SUBSPECIES', 'VARIETY', 'SUBVARIETY', 'FORM', 'SUBFORM', 'CULTIVAR', 'INFRASPECIFIC_NAME'];

function acceptedRank(rank: string, canonicalName: string, name: string, genus: boolean): boolean {
  return ranks.includes(rank) || (genus && rank === 'GENUS' && canonicalName === name);
}

type ReferencePhoto = { identifier: string; creator: string; license: 'CC BY 4.0' | 'CC0'; source_url: string };
export async function referencePhoto(key: number): Promise<ReferencePhoto | null> {
  const base = config().gbifBaseUrl.href;
  const url = new URL('occurrence/search', base.endsWith('/') ? base : base + '/');
  url.search = new URLSearchParams({ taxonKey: String(key), mediaType: 'StillImage', limit: '1' }).toString();
  url.searchParams.append('license', 'CC0_1_0');
  url.searchParams.append('license', 'CC_BY_4_0');
  let body;
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(3000) });
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

// Rank lookup is optional: identification remains usable during a GBIF outage.
export async function speciesRank(key: number): Promise<string | undefined> {
  const base = config().gbifBaseUrl.href;
  const url = new URL('species/' + key, base.endsWith('/') ? base : base + '/');
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(3000) });
    if (!response.ok) return;
    const species = await response.json();
    if (species?.key === key && species.kingdom === 'Plantae' && ranks.includes(species.rank)) return species.rank;
  } catch { /* Identification still has Pl@ntNet's GBIF key and canonical name. */ }
}

export async function matchSpecies(name: string, { alternatives = true, genus = false } = {}): Promise<Candidate[]> {
  const base = config().gbifBaseUrl.href;
  const url = new URL('species/match', base.endsWith('/') ? base : base + '/');
  url.search = new URLSearchParams({ name, kingdom: 'Plantae', verbose: 'true' }).toString();
  const signal = AbortSignal.timeout(3000);
  let match: Match;
  try {
    const response = await fetch(url, { signal });
    if (!response.ok) throw new Error('GBIF request failed');
    match = await response.json();
    if (!match || typeof match !== 'object' || typeof match.matchType !== 'string' || (match.alternatives !== undefined && !Array.isArray(match.alternatives))) throw new Error('Invalid GBIF response');
  } catch {
    error(503, 'Species search is unavailable. GBIF did not respond. Try again.');
  }
  if (match.matchType === 'NONE') error(422, 'No plant species matched that name. Try another name.');
  const candidates: Candidate[] = [];
  for (const [index, item] of [match, ...(alternatives ? (match.alternatives ?? []).slice(0, 5) : [])].entries()) {
    if (!item || item.kingdom !== 'Plantae' || !acceptedRank(item.rank, item.canonicalName, name, genus) || (index > 0 && item.rank !== 'SPECIES') || item.matchType === 'NONE') continue;
    if (!Number.isInteger(item.usageKey) || item.usageKey <= 0 || typeof item.scientificName !== 'string' || typeof item.canonicalName !== 'string') continue;
    const key = item.acceptedUsageKey ?? item.usageKey;
    const confidence = typeof item.confidence === 'number' && Number.isFinite(item.confidence) ? item.confidence : 0;
    if (!Number.isInteger(key) || key <= 0 || candidates.some(candidate => candidate.gbif_key === key)) continue;
    let scientificName = item.scientificName;
    let canonicalName = item.canonicalName;
    let rank = item.rank;
    if (item.acceptedUsageKey !== undefined) {
      if (typeof item.acceptedScientificName === 'string' && item.acceptedScientificName.trim() && typeof item.acceptedCanonicalName === 'string' && item.acceptedCanonicalName.trim()) {
        scientificName = item.acceptedScientificName;
        canonicalName = item.acceptedCanonicalName;
      } else {
        try {
          const response = await fetch(new URL(String(key), url), { signal });
          if (!response.ok) throw new Error('GBIF request failed');
          const accepted = await response.json();
          if (!accepted || accepted.key !== key || typeof accepted.scientificName !== 'string' || !accepted.scientificName.trim() || typeof accepted.canonicalName !== 'string' || !accepted.canonicalName.trim() || accepted.kingdom !== 'Plantae' || !acceptedRank(accepted.rank, accepted.canonicalName, name, genus)) throw new Error('Invalid GBIF accepted species');
          scientificName = accepted.scientificName;
          canonicalName = accepted.canonicalName;
          rank = accepted.rank;
        } catch {
          if (index > 0) continue;
          error(503, 'Species search is unavailable. GBIF did not return the accepted species. Try again.');
        }
      }
    }
    candidates.push({
      gbif_key: key, scientific_name: scientificName, canonical_name: canonicalName,
      rank, match_type: typeof item.matchType === 'string' ? item.matchType : 'UNKNOWN', confidence,
      requires_confirmation: index > 0 || item.matchType !== 'EXACT' || confidence < 90 || rank !== 'SPECIES',
    });
  }
  return candidates;
}

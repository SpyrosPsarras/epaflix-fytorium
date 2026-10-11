import { error } from '@sveltejs/kit';
import { config } from './config';
import type { Candidate } from '$lib/plants';

type Match = {
  usageKey: number; scientificName: string; canonicalName: string; rank: string; kingdom: string;
  matchType: string; confidence: number; acceptedUsageKey?: number;
  acceptedScientificName?: string; acceptedCanonicalName?: string; alternatives?: Match[];
};

export async function matchSpecies(name: string): Promise<Candidate[]> {
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
  const ranks = ['SPECIES', 'SUBSPECIES', 'VARIETY', 'SUBVARIETY', 'FORM', 'SUBFORM', 'CULTIVAR', 'INFRASPECIFIC_NAME'];
  const candidates: Candidate[] = [];
  for (const [index, item] of [match, ...(match.alternatives ?? [])].entries()) {
    if (!item || item.kingdom !== 'Plantae' || !ranks.includes(item.rank) || (index > 0 && item.rank !== 'SPECIES') || item.matchType === 'NONE') continue;
    if (!Number.isInteger(item.usageKey) || item.usageKey <= 0 || typeof item.scientificName !== 'string' || typeof item.canonicalName !== 'string') continue;
    const key = item.acceptedUsageKey ?? item.usageKey;
    const confidence = typeof item.confidence === 'number' && Number.isFinite(item.confidence) ? item.confidence : 0;
    if (!Number.isInteger(key) || key <= 0) continue;
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
          if (!accepted || accepted.key !== key || typeof accepted.scientificName !== 'string' || !accepted.scientificName.trim() || typeof accepted.canonicalName !== 'string' || !accepted.canonicalName.trim() || accepted.kingdom !== 'Plantae' || !ranks.includes(accepted.rank)) throw new Error('Invalid GBIF accepted species');
          scientificName = accepted.scientificName;
          canonicalName = accepted.canonicalName;
          rank = accepted.rank;
        } catch {
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

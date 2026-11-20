import type { Candidate } from '$lib/plants';

const words = (name: string) => name.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
  .replace(/ø/g, 'o').replace(/æ/g, 'ae').replace(/ς/g, 'σ').match(/[\p{L}\p{N}]+/gu) ?? [];
export const searchNameKey = (name: string) => words(name).join('');

export function wordsMatch(query: string, name: string) {
  const prefixes = words(query);
  const nameWords = words(name);
  return prefixes.length > 0 && prefixes.every(prefix => nameWords.some(word => word.startsWith(prefix)));
}

export function isPlaceholder(candidate: Pick<Candidate, 'canonical_name' | 'rank'>) {
  const [, epithet] = candidate.canonical_name.trim().toLowerCase().split(/\s+/);
  return (candidate.rank === 'SPECIES' && !epithet) || ['spec.', 'spec', 'sp.', 'sp', 'hybrid', 'hybr.'].includes(epithet);
}

export function rankCandidates<T extends { candidate: Candidate; exact: boolean }>(...stages: T[][]) {
  const entries = stages.flat().filter(({ candidate }) => !isPlaceholder(candidate));
  const keys = new Set<number>();
  const unique = [...entries.filter(entry => entry.exact), ...entries.filter(entry => !entry.exact)].filter(({ candidate }) => {
    if (keys.has(candidate.gbif_key)) return false;
    keys.add(candidate.gbif_key);
    return true;
  });
  return unique.slice(0, Math.max(10, unique.filter(entry => entry.exact).length));
}

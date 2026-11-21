import { expect, test } from 'vitest';
import { isPlaceholder, rankCandidates, wordsMatch } from '../../src/lib/server/species-ranking';

const row = (gbif_key: number, canonical_name: string, exact = false) => ({ exact, candidate: {
  gbif_key, canonical_name, scientific_name: canonical_name + ' Inventor', rank: 'SPECIES',
  match_type: null, confidence: null, requires_confirmation: !exact,
} });

test.each([
  ['rub pla', 'Common rubber-plant', true],
  ['plant rubber', 'Rúbber Plant', true],
  ['rubber plant', 'Spider Plant', false],
  ['rubber plant', 'Unrubber Plant', false],
])('every query word in %s must start a name word in %s', (query, name, matches) => {
  expect(wordsMatch(query, name)).toBe(matches);
});

test.each([
  'Sansevieria', 'Sansevieria spec.', 'Sansevieria spec', 'Dracaena sp. Inventor', 'Dracaena sp',
  'Dracaena hybrid', 'Dracaena hybr.',
])('filters placeholder %s', name => {
  expect(isPlaceholder(row(1, name).candidate)).toBe(true);
});

test.each([
  'Sinningia speciosa', 'Lilium speciosum', 'Hylotelephium spectabile', 'Petunia × hybrida', 'Dracaena trifasciata',
])('keeps named species %s', name => {
  expect(isPlaceholder(row(1, name).candidate)).toBe(false);
});

test('moves exact names ahead of the stages, keeps the first accepted key, and caps the list', () => {
  const local = [row(1, 'Testoria localis'), row(2, 'Testoria communis')];
  const wiki = [row(3, 'Testoria prima'), row(2, 'Testoria communis'), row(4, 'Testoria exacta', true)];
  const gbif = [row(5, 'Testoria remota'), row(3, 'Testoria prima'), ...Array.from({ length: 10 }, (_, index) => row(6 + index, 'Testoria diversa'))];
  expect(rankCandidates(local, wiki, gbif).map(({ candidate }) => candidate.gbif_key)).toEqual([4, 1, 2, 3, 5, 6, 7, 8, 9, 10]);
  expect(rankCandidates(local, wiki, gbif)[2]).toBe(local[1]);
});

test('keeps every distinct exact match when more than ten species share the name', () => {
  const exact = Array.from({ length: 12 }, (_, index) => row(index + 1, 'Testoria communis', true));
  const other = row(13, 'Testoria diversa');
  expect(rankCandidates([other, ...exact], [exact[0]]).map(({ candidate }) => candidate.gbif_key))
    .toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
});

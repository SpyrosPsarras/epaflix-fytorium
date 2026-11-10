import { readFileSync } from 'node:fs';
import { expect, test } from 'vitest';
import { pickCommonName } from '../../src/lib/server/common-name';

const fixture = readFileSync('tests/unit/fixtures/common-names.tsv', 'utf8')
  .trim().split('\n').filter(line => !line.startsWith('#')).map(line => {
    const [canonical_name, language, source, name] = line.split('\t');
    return { canonical_name, language, source, name };
  });

test.each([
  ['Beaucarnea recurvata', 'Ponytail', null],
  ['Chlorophytum comosum', 'Spider Plant', null],
  ['Coleus scutellarioides', 'Painted nettle', null],
  ['Crassula ovata', 'Jade Plant', 'Pengetre'],
  ['Dracaena trifasciata', 'Snake plant', 'Svigermorstunge'],
  ['Fragaria ananassa', 'Strawberry', 'Hagejordbær'],
  ['Monstera deliciosa', 'Swiss Cheese Plant', 'Adams ribbein'],
  ['Paeonia lactiflora', 'Chinese peony', 'Silkepion'],
  ['Thymus vulgaris', 'Thyme', 'Kryddertimian'],
])('picks the fixture names for %s', (species, en, nb) => {
  for (const [language, expected] of [['en', en], ['nb', nb]]) {
    expect(pickCommonName(species!, fixture.filter(row => row.canonical_name === species && row.language === language))).toBe(expected);
  }
});

test('splits lists, drops Latin names and genus names, and returns null without vernacular names', () => {
  expect(pickCommonName('Cóleus scutellarioides', [
    { source: 'owner', name: 'coleus; COLEUS SCUTELLARIOIDES, ; painted nettle' },
  ])).toBe('Painted nettle');
  expect(pickCommonName('Coleus scutellarioides', [{ source: 'gbif', name: 'Coleus; Coleus scutellarioides' }])).toBeNull();
  expect(pickCommonName('Coleus scutellarioides', [])).toBeNull();
});

test('owner names precede NorTaxa names and shortest then alphabetical breaks their ties', () => {
  const candidates = [
    { source: 'gbif', name: 'Common leaf' }, { source: 'wikidata', name: 'Common leaf' },
    { source: 'nortaxa', name: 'Long north leaf; birch; alder' },
  ];
  expect(pickCommonName('Syntheticia testensis', candidates)).toBe('Alder');
  expect(pickCommonName('Syntheticia testensis', [...candidates,
    { source: 'owner', name: 'Long owner leaf; zinnia; aster' },
  ])).toBe('Aster');
});

test('counts spelling variants together and selects their most frequent display spelling', () => {
  expect(pickCommonName('Syntheticia testensis', [
    { source: 'gbif', name: 'spiderplant; Spider-plant; Spider plant; Other leaf' },
    { source: 'wikidata', name: 'Other leaf' },
  ])).toBe('Spider plant');
  expect(pickCommonName('Syntheticia testensis', [
    { source: 'gbif', name: 'jadeplant; jade-plant' },
  ])).toBe('Jade plant');
});

test('uses word frequency then key length then alphabet for equal group counts', () => {
  expect(pickCommonName('Syntheticia testensis', [
    { source: 'gbif', name: 'Garden strawberry; Strawberry; Cultivated strawberry; Dessert strawberry' },
  ])).toBe('Strawberry');
  expect(pickCommonName('Syntheticia testensis', [{ source: 'gbif', name: 'Longleaf; Elm' }])).toBe('Elm');
  expect(pickCommonName('Syntheticia testensis', [{ source: 'gbif', name: 'Oak; Elm' }])).toBe('Elm');
});

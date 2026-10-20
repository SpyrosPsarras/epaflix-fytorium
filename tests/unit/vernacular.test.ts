import { expect, test } from 'vitest';
import { requestLanguage, vernacularName } from '../../src/lib/names';

test('requestLanguage picks the first supported language and falls back to English', () => {
  expect(requestLanguage('el-GR,el;q=0.9,en;q=0.8')).toBe('el');
  expect(requestLanguage('nn-NO')).toBe('nb');
  expect(requestLanguage('no')).toBe('nb');
  expect(requestLanguage('de-DE,nb;q=0.5')).toBe('nb');
  expect(requestLanguage('fr-FR')).toBe('en');
  expect(requestLanguage(null)).toBe('en');
  expect(requestLanguage('  NB-no , en')).toBe('nb');
  expect(requestLanguage("el'; SELECT 1;--")).toBe('en');
});

test.each([
  ['en;q=0.1, el;q=0.9', 'el'],
  ['el;q=0, nb', 'nb'],
  ['nb;q=0.8, el;q=0.8', 'nb'],
  ['el;q=0.8, nb;q=0.8', 'el'],
  ['de;q=1, nn-NO;q=0.7, en;q=0.2', 'nb'],
])('requestLanguage respects preference weights in %s', (header, expected) => {
  expect(requestLanguage(header)).toBe(expected);
});

test('chooses a valid English name, never a Latin or unrelated name', () => {
  const names = [{ name: 'Synthetic lantern', language: 'en' as const }, { name: 'Δοκιμαστικό φυτό', language: 'el' as const }];
  expect(vernacularName(names)).toBe('Synthetic lantern');
  expect(vernacularName([{ name: 'Testoria lucida', language: 'la' }, { name: 'Δοκιμαστικό φυτό', language: 'el' }])).toBeNull();
  expect(vernacularName([{ name: 'x'.repeat(201), language: 'en' }])).toBeNull();
  expect(vernacularName([{ name: '', language: 'en' }, { name: 'bad\nname', language: 'en' }])).toBeNull();
});

import { expect, test } from 'vitest';
import { vernacularName } from '../../src/lib/names';

test('chooses a valid English name, never a Latin or unrelated name', () => {
  const names = [{ name: 'Synthetic lantern', language: 'en' as const }, { name: 'Δοκιμαστικό φυτό', language: 'el' as const }];
  expect(vernacularName(names)).toBe('Synthetic lantern');
  expect(vernacularName([{ name: 'Testoria lucida', language: 'la' }, { name: 'Δοκιμαστικό φυτό', language: 'el' }])).toBeNull();
  expect(vernacularName([{ name: 'x'.repeat(201), language: 'en' }])).toBeNull();
  expect(vernacularName([{ name: '', language: 'en' }, { name: 'bad\nname', language: 'en' }])).toBeNull();
});

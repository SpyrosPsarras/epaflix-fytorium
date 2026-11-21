import { afterEach, expect, test, vi } from 'vitest';
import { wikidataJson } from '../../src/lib/server/name-sources/wikidata';
vi.mock('../../src/lib/server/config', () => ({ config: () => ({ wikidataBaseUrl: new URL('https://wikidata.example/'), origin: new URL('https://app.example'), testClockEnabled: true }) }));
vi.mock('../../src/lib/server/clock', () => ({ now: () => new Date('2030-06-01T00:00:00Z') }));
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

test('a frozen test clock holds the Wikidata request limit only until a real minute passes', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-21T10:00:00Z'));
  vi.stubGlobal('fetch', async () => Response.json({ query: { search: [] } }));
  for (let i = 0; i < 20; i++) await wikidataJson({ action: 'query' });
  await expect(wikidataJson({ action: 'query' })).rejects.toThrow('Wikidata request limit');
  vi.setSystemTime(new Date('2026-11-21T10:01:00Z'));
  await expect(wikidataJson({ action: 'query' })).resolves.toEqual({ query: { search: [] } });
});

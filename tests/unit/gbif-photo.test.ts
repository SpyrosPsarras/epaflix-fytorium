import { afterEach, expect, test, vi } from 'vitest';
import { referencePhoto } from '../../src/lib/server/gbif';
vi.mock('../../src/lib/server/config', () => ({ config: () => ({ gbifBaseUrl: new URL('https://gbif.example/v1/') }) }));
afterEach(() => vi.unstubAllGlobals());

test('reads the first occurrence media with credit and restricts the request to reusable still images', async () => {
  let requested: URL | undefined;
  vi.stubGlobal('fetch', async (url: URL) => {
    requested = url;
    return Response.json({ results: [{ references: 'https://example.org/observation', media: [
      { identifier: 'https://images.example/photo.jpg', license: 'http://creativecommons.org/licenses/by/4.0/', rightsHolder: 'Synthetic rights holder' },
    ] }] });
  });
  expect(await referencePhoto(910701)).toEqual({ identifier: 'https://images.example/photo.jpg', creator: 'Synthetic rights holder', license: 'CC BY 4.0', source_url: 'https://example.org/observation' });
  expect(requested?.pathname).toBe('/v1/occurrence/search');
  expect(requested?.searchParams.get('taxonKey')).toBe('910701');
  expect(requested?.searchParams.getAll('license')).toEqual(['CC0_1_0', 'CC_BY_4_0']);
  expect(requested?.searchParams.get('mediaType')).toBe('StillImage');
  expect(requested?.searchParams.get('limit')).toBe('1');
});

test.each([
  { results: [] },
  { results: [{ media: [] }] },
  { results: [{ references: 'https://example.org', media: [{ identifier: 'https://images.example/x', license: 'https://creativecommons.org/licenses/by-nc/4.0/' }] }] },
  { results: [{ references: 'javascript:alert(1)', media: [{ identifier: 'https://images.example/x', license: 'https://creativecommons.org/publicdomain/zero/1.0/' }] }] },
])('returns no reference for missing photos, incompatible licences or unsafe credit links %#', async body => {
  vi.stubGlobal('fetch', async () => Response.json(body));
  expect(await referencePhoto(910701)).toBeNull();
});

test('reports upstream failure instead of caching it as no photo', async () => {
  vi.stubGlobal('fetch', async () => new Response('', { status: 503 }));
  await expect(referencePhoto(910701)).rejects.toMatchObject({ status: 503 });
});

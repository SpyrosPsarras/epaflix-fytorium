import { beforeAll, expect, test } from 'vitest';
import sharp from 'sharp';
import { createApiHarness } from './harness';
import { createImageHost } from './planta-images';

const env: Record<string, string> = {};
const images = createImageHost(env);
const harness = createApiHarness({ testClock: true, appEnv: env });
let owner: Awaited<ReturnType<typeof harness.login>>;
beforeAll(async () => { owner = await harness.login(); });
const path = (key: number | string) => `/api/species/${key}/photo`;
const occurrence = (identifier: string, license = 'http://creativecommons.org/licenses/by/4.0/') => ({
  results: [{ references: 'https://example.org/synthetic-observation', media: [{ identifier, license, creator: 'Synthetic photographer' }] }],
});

test('serves a sanitised reference thumbnail and attribution from one persistent GBIF lookup', async () => {
  harness.setGbifOccurrences(910701, occurrence(images.url));
  const credit = await owner.request(path(910701) + '/credit');
  expect(credit.status).toBe(200);
  expect(await credit.json()).toEqual({ common_name: null, photo: { creator: 'Synthetic photographer', license: 'CC BY 4.0', source_url: 'https://example.org/synthetic-observation' } });
  const response = await owner.request(path(910701));
  expect(response.status).toBe(200);
  expect(response.headers.get('content-type')).toBe('image/jpeg');
  expect(response.headers.get('cache-control')).toBe('private, max-age=86400');
  const bytes = Buffer.from(await response.arrayBuffer());
  const meta = await sharp(bytes).metadata();
  expect(meta.width).toBe(400);
  expect(meta.height).toBe(300);
  expect(meta.exif).toBeUndefined();
  expect(meta.orientation).toBeUndefined();
  await harness.restartApp();
  const cached = await owner.request(path(910701));
  expect(Buffer.from(await cached.arrayBuffer())).toEqual(bytes);
  expect(harness.occurrenceRequests).toHaveLength(1);
  expect(harness.occurrenceRequests[0].searchParams.getAll('license')).toEqual(['CC0_1_0', 'CC_BY_4_0']);
  expect(harness.occurrenceRequests[0].searchParams.get('mediaType')).toBe('StillImage');
});

test('persists the no-photo outcome and refreshes after thirty days', async () => {
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-10T12:00:00Z' });
  const before = harness.occurrenceRequests.length;
  expect(await (await owner.request(path(910702) + '/credit')).json()).toEqual({ common_name: null, photo: null });
  expect((await owner.request(path(910702))).status).toBe(404);
  await harness.restartApp();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-11T12:00:00Z' });
  expect(await (await owner.request(path(910702) + '/credit')).json()).toEqual({ common_name: null, photo: null });
  expect(harness.occurrenceRequests).toHaveLength(before + 1);
  harness.setGbifOccurrences(910702, occurrence(images.url, 'http://creativecommons.org/publicdomain/zero/1.0/'));
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-07-11T12:00:00Z' });
  expect(await (await owner.request(path(910702) + '/credit')).json()).toMatchObject({ photo: { license: 'CC0' } });
  expect(harness.occurrenceRequests).toHaveLength(before + 2);
});

test('temporarily caches GBIF outages and image failures, then recovers after one hour', async () => {
  for (const [key, failure] of [[910706, 'gbif'], [910707, 'image']] as const) {
    await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-07-12T12:00:00Z' });
    harness.setGbifOccurrences(key, occurrence(images.url), failure === 'gbif' ? 503 : 200);
    if (failure === 'image') await images.setStatus(503);
    const before = harness.occurrenceRequests.length;
    const responses = await Promise.all(Array.from({ length: 3 }, () => owner.request(path(key) + '/credit')));
    for (const response of responses) {
      expect(response.status).toBe(200);
      expect(response.headers.get('cache-control')).toBe('no-store');
      expect(await response.json()).toMatchObject({ photo: null });
    }
    expect(harness.occurrenceRequests).toHaveLength(before + 1);
    await harness.restartApp();
    await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-07-12T12:30:00Z' });
    expect((await owner.request(path(key))).status).toBe(404);
    expect(harness.occurrenceRequests).toHaveLength(before + 1);
    harness.setGbifOccurrences(key, occurrence(images.url));
    await images.setStatus(200);
    await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-07-12T13:00:01Z' });
    expect(await (await owner.request(path(key) + '/credit')).json()).toMatchObject({ photo: { license: 'CC BY 4.0' } });
    expect((await owner.request(path(key))).status).toBe(200);
    expect(harness.occurrenceRequests).toHaveLength(before + 2);
  }
});

test('retries failed common names after one hour even when the photo succeeds', async () => {
  const key = 910708;
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-07-13T12:00:00Z' });
  harness.setGbifOccurrences(key, occurrence(images.url));
  harness.setNames('gbif', {}, 503);
  const first = await (await owner.request(path(key) + '/credit')).json();
  expect(first).toMatchObject({ common_name: null, photo: { license: 'CC BY 4.0' } });
  expect(harness.nameCalls('gbif', key)).toBe(1);
  harness.setNames('gbif', { results: [{ vernacularName: 'Synthetic retryleaf', language: 'eng' }] });
  await harness.restartApp();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-07-13T12:59:59Z' });
  expect(await (await owner.request(path(key) + '/credit')).json()).toEqual(first);
  expect(harness.nameCalls('gbif', key)).toBe(1);
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-07-13T13:00:00Z' });
  expect(await (await owner.request(path(key) + '/credit')).json()).toMatchObject({ common_name: 'Synthetic retryleaf', photo: { license: 'CC BY 4.0' } });
  expect(harness.nameCalls('gbif', key)).toBe(2);
  harness.setNames('gbif', { results: [] });
});

test('refuses invalid keys before querying GBIF and refuses private image hosts', async () => {
  const before = harness.occurrenceRequests.length;
  for (const key of ['bad', '0', '-1', '1.5', '2147483648']) {
    expect((await owner.request(path(key))).status).toBe(422);
    expect((await owner.request(path(key) + '/credit')).status).toBe(422);
  }
  expect(harness.occurrenceRequests).toHaveLength(before);
  harness.setGbifOccurrences(910703, occurrence('https://127.0.0.1/private.jpg'));
  expect((await owner.request(path(910703))).status).toBe(404);
});

test('vernacular names share the persistent photo cache even when no photo exists', async () => {
  harness.setNames('gbif', { results: [{ vernacularName: 'Synthetic lantern', language: 'eng' }, { vernacularName: 'Δοκιμαστικό φυτό', language: 'ell' }] });
  expect(await (await owner.request(path(910705) + '/credit')).json()).toEqual({ photo: null, common_name: 'Synthetic lantern' });
  harness.setNames('gbif', { results: [] });
  await harness.restartApp();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-07-11T12:00:00Z' });
  expect(await (await owner.request(path(910705) + '/credit')).json()).toEqual({ photo: null, common_name: 'Synthetic lantern' });
  expect(harness.nameCalls('gbif', 910705)).toBe(1);
});

test('species photo reads follow species search permissions', async () => {
  const familyInvite = await (await owner.request('/api/household/invites', 'POST', { role: 'family', plant_ids: [] })).json();
  const family = await harness.login('synthetic-photo-family', {}, familyInvite.link.split('/').at(-1));
  expect((await family.request(path(910704) + '/credit')).status).toBe(200);
  const sitterInvite = await (await owner.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [] })).json();
  const sitter = await harness.login('synthetic-photo-sitter', {}, sitterInvite.link.split('/').at(-1));
  for (const suffix of ['', '/credit']) {
    expect((await sitter.request(path(910704) + suffix)).status).toBe(403);
    expect((await owner.request(path(910704) + suffix, 'GET', undefined, '')).status).toBe(401);
  }
});

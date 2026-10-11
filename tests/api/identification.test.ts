import { beforeAll, expect, test } from 'vitest';
import { randomBytes } from 'node:crypto';
import sharp from 'sharp';
import pg from 'pg';
import { identificationResponse } from '../identification-fixture';
import exif from 'exif-reader';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true });
let owner: Awaited<ReturnType<typeof harness.login>>;
let gpsImage: Buffer;
let siteId: string;
beforeAll(async () => {
  owner = await harness.login();
  siteId = (await (await owner.request('/api/sites', 'POST', { name: 'Test identification site', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json()).id;
  gpsImage = await sharp({ create: { width: 800, height: 600, channels: 3, background: '#408040' } }).jpeg().withMetadata({ orientation: 6 }).withExifMerge({ IFD3: { GPSLatitudeRef: 'N', GPSLatitude: '1/1 2/1 3/1', GPSLongitudeRef: 'E', GPSLongitude: '4/1 5/1 6/1' } }).toBuffer();
});
function identify(images = [gpsImage, gpsImage], cookie = owner.cookie, origin = harness.base) {
  const form = new FormData();
  for (const image of images) form.append('images', new Blob([new Uint8Array(image)], { type: 'image/jpeg' }), 'synthetic.jpg');
  return fetch(harness.base + '/api/identification', { method: 'POST', headers: { cookie, origin }, body: form });
}

test('returns ranked candidates and sends only oriented, metadata-free images with organs to Pl@ntNet', async () => {
  const source = await sharp(gpsImage).metadata();
  expect(source.orientation).toBe(6);
  expect(exif(source.exif!).GPSInfo).toMatchObject({ GPSLatitude: [1, 2, 3], GPSLongitude: [4, 5, 6] });
  const before = await (await owner.request('/api/plants')).json();
  const response = await identify();
  expect(response.status).toBe(200);
  const result = await response.json();
  expect(result).toMatchObject({ search_id: expect.any(String), remaining: 42, candidates: [
    { gbif_key: 913001, scientific_name: 'Testoria picta Inventor', score: 0.97, common_names: ['Test paintedleaf'], requires_confirmation: true },
    { gbif_key: 910001, scientific_name: 'Testoria lucida Inventor', score: 0.21, common_names: ['Test brightleaf'], requires_confirmation: true },
  ] });
  expect(await (await owner.request('/api/plants')).json()).toEqual(before);
  const received = harness.identificationRequests.at(-1)!;
  expect(received.pathname).toBe('/identify/all');
  expect(received.relatedImages).toBe('false');
  const multipart = await new Response(new Uint8Array(received.bytes), { headers: { 'content-type': received.contentType } }).formData();
  expect(multipart.getAll('organs')).toEqual(['auto', 'auto']);
  const images = multipart.getAll('images');
  expect(images).toHaveLength(2);
  for (const image of images) {
    expect(image).toBeInstanceOf(File);
    if (!(image instanceof File)) throw new Error('Expected image');
    const metadata = await sharp(Buffer.from(await image.arrayBuffer())).metadata();
    expect(metadata).toMatchObject({ format: 'jpeg', width: 600, height: 800 });
    expect(metadata.exif).toBeUndefined();
    expect(metadata.orientation).toBeUndefined();
    expect(metadata.exif ? exif(metadata.exif).GPSInfo : undefined).toBeUndefined();
  }
});

test('accepts a full-resolution image over 3 MB and sends a metadata-free JPEG with a long edge at most 1600 px', async () => {
  const image = await sharp(randomBytes(2400 * 1800 * 3), { raw: { width: 2400, height: 1800, channels: 3 } }).jpeg({ quality: 100 }).withMetadata({ orientation: 6 }).withExifMerge({ IFD3: { GPSLatitudeRef: 'N', GPSLatitude: '1/1 2/1 3/1' } }).toBuffer();
  expect(image.length).toBeGreaterThan(3 * 1024 * 1024);
  expect(image.length).toBeLessThanOrEqual(15 * 1024 * 1024);
  expect((await identify([image])).status).toBe(200);
  const received = harness.identificationRequests.at(-1)!;
  const multipart = await new Response(new Uint8Array(received.bytes), { headers: { 'content-type': received.contentType } }).formData();
  const sent = multipart.get('images');
  if (!(sent instanceof File)) throw new Error('Expected image');
  const metadata = await sharp(Buffer.from(await sent.arrayBuffer())).metadata();
  expect(metadata).toMatchObject({ format: 'jpeg', width: 1200, height: 1600 });
  expect(metadata.exif).toBeUndefined();
  expect(metadata.orientation).toBeUndefined();
  expect(metadata.icc).toBeUndefined();
  expect(metadata.xmp).toBeUndefined();
});

const add = (search_id: string, extra = {}) => owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: siteId, search_id, gbif_key: 913001, ...extra });
test('requires an explicit confirmed pick from the household record and refuses forged keys and scores', async () => {
  const result = await (await identify()).json();
  const before = await (await owner.request('/api/plants')).json();
  for (const extra of [{}, { confirmed: false }, { confirmed: 'true' }, { confirmed: true, gbif_key: 999999 }, { confirmed: true, score: 1 }]) {
    expect((await add(result.search_id, extra)).status).toBe(422);
  }
  expect((await add('invented-record', { confirmed: true })).status).toBe(422);
  expect(await (await owner.request('/api/plants')).json()).toEqual(before);
  const saved = await add(result.search_id, { confirmed: true });
  expect(saved.status).toBe(201);
  const plant = await saved.json();
  expect(plant).toMatchObject({ gbif_key: 913001, scientific_name: 'Testoria picta Inventor', cover_photo_id: null });
  expect(await (await owner.request('/api/plants/' + plant.id + '/photos')).json()).toEqual([]);
  const care = await (await owner.request('/api/species/913001/care')).json();
  expect(care.fetch).toMatchObject({ status: 'pending', attempts: 0 });
  const species = await (await owner.request('/api/species/913001')).json();
  expect(species.names).toEqual(expect.arrayContaining([expect.objectContaining({ name: 'Testoria picta', language: 'la', source: 'gbif' })]));
});

test('keeps hybrid canonical names, uses known GBIF rank and does not overwrite stored taxonomy from identification', async () => {
  harness.setGbifSpecies(913010, { key: 913010, kingdom: 'Plantae', rank: 'VARIETY' });
  harness.setPlantnet({ results: [{ score: 0.9, species: { scientificNameWithoutAuthor: 'Testoria × hybrida', scientificNameAuthorship: 'Inventor' }, gbif: { id: '913010' } }] });
  const identified = await (await identify([gpsImage])).json();
  expect(identified.candidates[0]).toMatchObject({ canonical_name: 'Testoria × hybrida', rank: 'VARIETY' });
  const saved = await add(identified.search_id, { gbif_key: 913010, confirmed: true });
  expect(saved.status).toBe(201);
  expect(await saved.json()).toMatchObject({ canonical_name: 'Testoria × hybrida', rank: 'VARIETY' });

  harness.setGbif({ usageKey: 913010, scientificName: 'Testoria × hybrida var. alba Inventor', canonicalName: 'Testoria × hybrida var. alba', rank: 'SUBVARIETY', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const searched = await (await owner.request('/api/species/search?q=Testoria%20alba')).json();
  expect((await add(searched.search_id, { gbif_key: 913010, confirmed: true })).status).toBe(201);
  const again = await (await identify([gpsImage])).json();
  const duplicate = await add(again.search_id, { gbif_key: 913010, confirmed: true });
  expect(duplicate.status).toBe(201);
  expect(await duplicate.json()).toMatchObject({ canonical_name: 'Testoria × hybrida var. alba', rank: 'SUBVARIETY' });
  const species = await (await owner.request('/api/species/913010')).json();
  expect(species.names.filter((name: { language: string; source: string }) => name.language === 'la' && name.source === 'gbif')).toEqual([
    expect.objectContaining({ name: 'Testoria × hybrida var. alba' }),
  ]);
  const plants = await (await owner.request('/api/plants')).json();
  const previous = plants.find((plant: { gbif_key: number }) => plant.gbif_key === 913001);
  const change = { search_id: again.search_id, gbif_key: 913010 };
  expect((await owner.request('/api/plants/' + previous.id + '/species', 'POST', change)).status).toBe(422);
  const changed = await owner.request('/api/plants/' + previous.id + '/species', 'POST', { ...change, confirmed: true });
  expect(changed.status).toBe(200);
  expect(await changed.json()).toMatchObject({ canonical_name: 'Testoria × hybrida var. alba', rank: 'SUBVARIETY' });
  const history = await (await owner.request('/api/plants/' + previous.id + '/species-changes')).json();
  expect(history[0]).toMatchObject({ next_name: 'Testoria × hybrida var. alba Inventor' });
  harness.setPlantnet();
});

test('does not invent a species rank when GBIF has no known rank', async () => {
  harness.setPlantnet({ results: [{ score: 0.9, species: { scientificNameWithoutAuthor: 'Testoria incognita' }, gbif: { id: '913011' } }] });
  const response = await identify([gpsImage]);
  expect(response.status).toBe(200);
  const identified = await response.json();
  expect(identified.candidates[0]).toMatchObject({ canonical_name: 'Testoria incognita', rank: 'UNKNOWN' });
  const saved = await add(identified.search_id, { gbif_key: 913011, confirmed: true });
  expect(saved.status).toBe(201);
  expect(await saved.json()).toMatchObject({ canonical_name: 'Testoria incognita', rank: 'UNKNOWN' });
  harness.setPlantnet();
});

test('bounds image count and size, rejects non-images and never sends invalid input upstream', async () => {
  const calls = harness.identificationRequests.length;
  for (const count of [0, 6]) expect((await identify(Array.from({ length: count }, () => gpsImage))).status).toBe(422);
  const oversizedFile = await identify([Buffer.alloc(15 * 1024 * 1024 + 1)]);
  expect(oversizedFile.status).toBe(413);
  expect(await oversizedFile.json()).toMatchObject({ message: 'Each identification image must be at most 15 MB.' });
  for (const extraBytes of [1, 16 * 1024]) {
    const oversizedTotal = await identify(Array.from({ length: 5 }, () => Buffer.alloc(8 * 1024 * 1024 + extraBytes)));
    expect(oversizedTotal.status).toBe(413);
    expect(await oversizedTotal.json()).toMatchObject({ message: 'Identification photos must total at most 40 MB.' });
  }
  for (const bytes of [Buffer.from('not an image'), Buffer.alloc(0), gpsImage.subarray(0, 50), Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>')]) expect((await identify([bytes])).status).toBe(422);
  expect(harness.identificationRequests).toHaveLength(calls);
  for (const count of [1, 5]) expect((await identify(Array.from({ length: count }, () => gpsImage))).status).toBe(200);
});

test('drops an unmappable candidate and keeps confirmation for a low-confidence GBIF mapping', async () => {
  harness.setGbif({ matchType: 'NONE', confidence: 0 });
  const dropped = await (await identify([gpsImage])).json();
  expect(dropped.candidates.map((item: { gbif_key: number }) => item.gbif_key)).toEqual([913001]);
  harness.setGbif({ usageKey: 913002, scientificName: 'Testoria lucens Inventor', canonicalName: 'Testoria lucens', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'FUZZY', confidence: 72 });
  const mapped = await (await identify([gpsImage])).json();
  expect(mapped.candidates[1]).toMatchObject({ gbif_key: 913002, scientific_name: 'Testoria lucens Inventor', identification_name: 'Testoria lucida Inventor', score: 0.21, requires_confirmation: true, match_type: 'FUZZY', confidence: 72 });
  expect((await add(mapped.search_id, { gbif_key: 913002, confirmed: false })).status).toBe(422);
  expect((await add(mapped.search_id, { gbif_key: 913002, confirmed: true })).status).toBe(201);
});

test('drops a result without a GBIF id when only a GBIF alternative is usable', async () => {
  harness.setGbif({ usageKey: 913020, scientificName: 'Testoria Inventor', canonicalName: 'Testoria', rank: 'GENUS', kingdom: 'Plantae', matchType: 'HIGHERRANK', confidence: 99, alternatives: [
    { usageKey: 913021, scientificName: 'Testoria alternativa Inventor', canonicalName: 'Testoria alternativa', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'FUZZY', confidence: 70 },
  ] });
  const response = await identify([gpsImage]);
  expect(response.status).toBe(200);
  expect((await response.json()).candidates.map((item: { gbif_key: number }) => item.gbif_key)).toEqual([913001]);
});

test('stores the last quota across app restart, returns it when low and hides it when plentiful', async () => {
  expect((await identify([gpsImage])).status).toBe(200);
  await harness.restartApp();
  expect(await (await owner.request('/api/identification')).json()).toEqual({ remaining: 42 });
  harness.setPlantnet({ ...identificationResponse, remainingIdentificationRequests: 51 });
  expect(await (await identify([gpsImage])).json()).not.toHaveProperty('remaining');
  expect(await (await owner.request('/api/identification')).json()).toEqual({});
  harness.setPlantnet({ ...identificationResponse, remainingIdentificationRequests: 50 });
  expect(await (await identify([gpsImage])).json()).toHaveProperty('remaining', 50);
  expect(await (await owner.request('/api/identification')).json()).toEqual({ remaining: 50 });
});

test('reports exhausted quota, upstream rejection, service errors, invalid responses and bounded timeout clearly', async () => {
  for (const [status, delay, payload, expected, message] of [
    [429, 0, {}, 429, 'quota is exhausted'],
    [200, 0, { results: [], remainingIdentificationRequests: 0 }, 429, 'quota is exhausted'],
    [400, 0, {}, 422, 'could not identify'],
    [401, 0, {}, 503, 'API configuration'],
    [403, 0, {}, 503, 'API configuration'],
    [503, 0, {}, 503, 'Pl@ntNet'],
    [200, 25000, identificationResponse, 503, '20 seconds'],
    [200, 0, null, 503, 'invalid response'],
  ] as const) {
    harness.setPlantnet(payload, status, delay);
    const start = performance.now();
    const response = await identify([gpsImage]);
    expect(response.status).toBe(expected);
    expect((await response.json()).message).toContain(message);
    expect(performance.now() - start).toBeLessThan(24000);
    if (expected === 429) expect(await (await owner.request('/api/identification')).json()).toEqual({ remaining: 0 });
  }
  expect(harness.identificationKeyLeaked).toBe(false);
  harness.setPlantnet();
});

test('reports an empty API key as unconfigured without calling upstream', async () => {
  await harness.restartApp({ PLANTNET_API_KEY: '' });
  const calls = harness.identificationRequests.length;
  const response = await identify([gpsImage]);
  expect(response.status).toBe(503);
  expect((await response.json()).message).toContain('not configured');
  expect(harness.identificationRequests).toHaveLength(calls);
  await harness.restartApp();
});

test('requires login and same origin, expires choices and refuses picks across households', async () => {
  const calls = harness.identificationRequests.length;
  for (const cookie of ['', 'fytorium_session=invalid']) {
    expect((await identify([gpsImage], cookie)).status).toBe(401);
    expect((await owner.request('/api/identification', 'GET', undefined, cookie)).status).toBe(401);
  }
  expect((await identify([gpsImage], owner.cookie, 'https://untrusted.example')).status).toBe(403);
  expect(harness.identificationRequests).toHaveLength(calls);
  const result = await (await identify([gpsImage])).json();
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 300001 });
  expect((await add(result.search_id, { confirmed: true })).status).toBe(422);
  await owner.request('/__test/clock', 'POST', { action: 'reset' });
  const fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  try {
    // Invitations have no API yet. SQL supplies membership only.
    await fixtureDb.query('DROP INDEX households_singleton');
    const household = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test identification household') RETURNING id");
    await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-identification-other', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  } finally { await fixtureDb.end(); }
  const other = await harness.login('synthetic-identification-other');
  const foreign = await (await identify([gpsImage], other.cookie)).json();
  expect((await add(foreign.search_id, { confirmed: true })).status).toBe(422);
  const own = await (await identify([gpsImage])).json();
  const otherSite = (await (await other.request('/api/sites', 'POST', { name: 'Test foreign identification site', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json()).id;
  expect((await other.request('/api/plants', 'POST', { ...fixturePlant, site_id: otherSite, search_id: own.search_id, gbif_key: 913001, confirmed: true })).status).toBe(422);
});

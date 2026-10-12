import { beforeAll, expect, test } from 'vitest';
import sharp from 'sharp';
import exif from 'exif-reader';
import pg from 'pg';
import { randomUUID } from 'node:crypto';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ photos: true, testClock: true });
let owner: Awaited<ReturnType<typeof harness.login>>;
let plantId: string;
let gpsImage: Buffer;

beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2026-10-24T22:30:00Z' }) });
  owner = await harness.login();
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Test photo room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  const added = await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 });
  expect(added.status).toBe(201);
  plantId = (await added.json()).id;
  gpsImage = await sharp({ create: { width: 800, height: 600, channels: 3, background: '#408040' } }).jpeg().withMetadata({ orientation: 6 }).withExifMerge({ IFD3: { GPSLatitudeRef: 'N', GPSLatitude: '1/1 2/1 3/1', GPSLongitudeRef: 'E', GPSLongitude: '4/1 5/1 6/1' } }).toBuffer();
});

function upload(bytes = gpsImage, fields: Record<string, string | undefined> = {}, cookie = owner.cookie, plant = plantId) {
  const form = new FormData();
  form.set('photo', new Blob([new Uint8Array(bytes)], { type: 'image/jpeg' }), 'synthetic.jpg');
  for (const [key, value] of Object.entries(fields)) if (value !== undefined) form.set(key, value);
  return fetch(harness.base + '/api/plants/' + plant + '/photos', { method: 'POST', headers: { cookie, origin: harness.base }, body: form });
}
async function download(key: string) {
  const stream = await harness.storage.getObject(harness.bucket, key);
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(chunk);
  return Buffer.concat(chunks);
}
const updates = () => owner.request('/api/plants/' + plantId + '/photos').then(response => response.json());
async function objectKeys() {
  const keys: string[] = [];
  for await (const object of harness.storage.listObjectsV2(harness.bucket, '', true)) keys.push(object.name);
  return keys.sort();
}

// The S3 read is the agreed storage boundary, not a query against DB internals.
test('checks whether the plant exists before rejecting an undecodable photo', async () => {
  const missing = randomUUID();
  const undecodable = Buffer.from('synthetic-not-an-image');
  expect((await upload(undecodable, {}, owner.cookie, missing)).status).toBe(404);
  expect((await upload(undecodable)).status).toBe(422);
  expect((await upload(gpsImage, {}, owner.cookie, missing)).status).toBe(404);
});

test('stores an oriented original and thumbnail without EXIF GPS, privately served after login', async () => {
  const source = await sharp(gpsImage).metadata();
  expect(source.orientation).toBe(6);
  expect(source.exif).toBeDefined();
  expect(exif(source.exif!).GPSInfo).toMatchObject({ GPSLatitudeRef: 'N', GPSLatitude: [1, 2, 3], GPSLongitudeRef: 'E', GPSLongitude: [4, 5, 6] });
  const response = await upload(gpsImage, { note: 'Test new leaf', health: 'good' });
  expect(response.status).toBe(201);
  const photo = await response.json();
  expect(photo).toMatchObject({ plant_id: plantId, note: 'Test new leaf', health: 'good', taken_on: '2026-10-25', is_cover: false });
  expect(photo.original_key).toMatch(new RegExp('^photos/[^/]+/' + plantId + '/[0-9a-f-]+-original\\.jpg$'));
  const original = await download(photo.original_key);
  const thumbnail = await download(photo.thumb_key);
  for (const bytes of [original, thumbnail]) {
    const metadata = await sharp(bytes).metadata();
    expect(metadata.format).toBe('jpeg');
    expect(metadata.exif).toBeUndefined();
    expect(metadata.orientation).toBeUndefined();
    expect(metadata.exif ? exif(metadata.exif).GPSInfo : undefined).toBeUndefined();
  }
  expect(await sharp(original).metadata()).toMatchObject({ width: 600, height: 800 });
  expect(await sharp(thumbnail).metadata()).toMatchObject({ width: 300, height: 400 });
  const served = await owner.request('/api/photos/' + photo.id + '/original');
  expect(served.status).toBe(200);
  expect(served.headers.get('content-type')).toBe('image/jpeg');
  expect(Buffer.from(await served.arrayBuffer())).toEqual(original);
  expect(await updates()).toContainEqual(photo);
});

test('keeps photo-update originals wider than the identification resize limit at full width', async () => {
  const image = await sharp({ create: { width: 2400, height: 1800, channels: 3, background: '#608060' } }).jpeg().toBuffer();
  const response = await upload(image);
  expect(response.status).toBe(201);
  const photo = await response.json();
  expect(await sharp(await download(photo.original_key)).metadata()).toMatchObject({ width: 2400, height: 1800 });
  // Keep the shared history fixture unchanged for the dated-health scenarios below.
  expect((await owner.request('/api/photos/' + photo.id, 'DELETE')).status).toBe(204);
});

test('refuses oversized, corrupt, non-image and invalid dated updates without rows or objects', async () => {
  const before = await updates();
  const keys = await objectKeys();
  const notMultipart = await owner.request('/api/plants/' + plantId + '/photos', 'POST', {});
  expect(notMultipart.status).toBe(400);
  expect(await notMultipart.json()).toMatchObject({ message: 'Send a photo form.' });
  const oversized = Buffer.alloc(15 * 1024 * 1024 + 1);
  for (const bytes of [oversized, Buffer.alloc(15 * 1024 * 1024 + 64 * 1024 + 1)]) {
    const response = await upload(bytes);
    expect(response.status).toBe(413);
    expect(await response.json()).toMatchObject({ message: 'Photos must be at most 15 MB.' });
  }
  for (const bytes of [Buffer.from('not an image'), gpsImage.subarray(0, 50), Buffer.alloc(0), Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>')]) expect((await upload(bytes)).status).toBe(422);
  for (const fields of [{ taken_on: '2026-10-26' }, { taken_on: '2026-02-30' }, { taken_on: 'bad-date' }, { health: 'excellent' }, { note: 'x'.repeat(5001) }]) expect((await upload(gpsImage, fields)).status).toBe(422);
  expect(await updates()).toEqual(before);
  expect(await objectKeys()).toEqual(keys);
});

test('switches a single cover even for concurrent choices and exposes it on plant reads', async () => {
  const first = (await updates())[0];
  const secondResponse = await upload(gpsImage, { taken_on: '2026-10-20' });
  expect(secondResponse.status).toBe(201);
  const second = await secondResponse.json();
  const cover = (id: string) => owner.request('/api/photos/' + id + '/cover', 'PUT');
  expect((await cover(first.id)).status).toBe(200);
  expect((await (await owner.request('/api/plants/' + plantId)).json()).cover_photo_id).toBe(first.id);
  expect((await cover(second.id)).status).toBe(200);
  expect((await updates()).filter((photo: { is_cover: boolean }) => photo.is_cover).map((photo: { id: string }) => photo.id)).toEqual([second.id]);
  expect(await (await owner.request('/api/plants')).json()).toContainEqual(expect.objectContaining({ id: plantId, cover_photo_id: second.id }));
  for (const response of await Promise.all([cover(first.id), cover(second.id), cover(first.id)])) expect(response.status).toBe(200);
  expect((await updates()).filter((photo: { is_cover: boolean }) => photo.is_cover)).toHaveLength(1);
});

test('sets health directly in settings and only lets the newest dated photo health replace it', async () => {
  const plant = () => owner.request('/api/plants/' + plantId).then(response => response.json());
  expect((await plant()).health).toBe('good');
  expect((await upload(gpsImage, { taken_on: '2026-10-19', health: 'poor' })).status).toBe(201);
  expect((await plant()).health).toBe('good');
  expect((await owner.request('/api/plants/' + plantId, 'PUT', { ...await plant(), health: 'fair' })).status).toBe(200);
  expect((await plant()).health).toBe('fair');
  for (const health of ['excellent', '', 12, false]) expect((await owner.request('/api/plants/' + plantId, 'PUT', { ...await plant(), health })).status).toBe(422);
  expect((await plant()).health).toBe('fair');
  // Equal taken_on dates use arrival order, even with a fixed test clock.
  expect((await upload(gpsImage, { taken_on: '2026-10-25', health: 'very_good' })).status).toBe(201);
  expect((await plant()).health).toBe('very_good');
  expect((await upload(gpsImage, { taken_on: '2026-10-25' })).status).toBe(201);
  expect((await plant()).health).toBe('very_good');
  expect((await owner.request('/api/plants/' + plantId, 'PUT', { ...await plant(), health: null })).status).toBe(200);
  expect((await plant()).health).toBeNull();
});

test('browses all plants by taken date then creation time, while plant updates stay separate', async () => {
  const site = (await (await owner.request('/api/sites')).json())[0];
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  const secondPlant = await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname: 'Test second photo plant' })).json();
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 1000 });
  const second = await (await upload(gpsImage, { taken_on: '2026-10-25', note: 'Test second plant update' }, owner.cookie, secondPlant.id)).json();
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 1000 });
  const recent = await (await upload(gpsImage, { taken_on: '2026-10-25', note: 'Test recent update' })).json();
  const old = await (await upload(gpsImage, { taken_on: '2020-01-02', note: 'Test past update' })).json();
  expect(old.taken_on).toBe('2020-01-02');
  const response = await owner.request('/api/journal');
  expect(response.status).toBe(200);
  const journal = await response.json();
  expect(journal[0].id).toBe(recent.id);
  expect(journal[1]).toMatchObject({ id: second.id, plant_name: 'Test second photo plant' });
  expect(journal.at(-1).id).toBe(old.id);
  expect((await updates()).map((photo: { id: string }) => photo.id)).not.toContain(second.id);
});

test('deletes both stored objects and the update, clearing a deleted cover', async () => {
  const photo = (await updates())[0];
  expect((await owner.request('/api/photos/' + photo.id + '/cover', 'PUT')).status).toBe(200);
  const before = await objectKeys();
  expect(before).toContain(photo.original_key);
  expect(before).toContain(photo.thumb_key);
  const deleted = await owner.request('/api/photos/' + photo.id, 'DELETE');
  expect(deleted.status).toBe(204);
  expect((await updates()).map((update: { id: string }) => update.id)).not.toContain(photo.id);
  expect(await objectKeys()).toEqual(before.filter(key => key !== photo.original_key && key !== photo.thumb_key));
  expect((await (await owner.request('/api/plants/' + plantId)).json()).cover_photo_id).toBeNull();
  expect((await owner.request('/api/photos/' + photo.id + '/original')).status).toBe(404);
  expect((await owner.request('/api/photos/' + photo.id, 'DELETE')).status).toBe(404);
});

test('removes the original after a real S3 thumbnail write failure and leaves no update', async () => {
  const before = await updates();
  const keys = await objectKeys();
  harness.failThumbnailWrites(true);
  try { expect((await upload()).status).toBe(500); }
  finally { harness.failThumbnailWrites(false); }
  expect(await updates()).toEqual(before);
  expect(await objectKeys()).toEqual(keys);
  expect((await upload(gpsImage, { note: 'Test recovered storage' })).status).toBe(201);
});

test('requires login and refuses cross-origin changes and anonymous direct S3 reads', async () => {
  const photo = (await updates())[0];
  const before = await updates();
  const keys = await objectKeys();
  for (const cookie of ['', 'fytorium_session=invalid']) {
    expect((await upload(gpsImage, {}, cookie)).status).toBe(401);
    for (const [path, method] of [
      ['/api/plants/' + plantId + '/photos', 'GET'], ['/api/journal', 'GET'],
      ['/api/photos/' + photo.id + '/original', 'GET'], ['/api/photos/' + photo.id + '/thumb', 'GET'],
      ['/api/photos/' + photo.id + '/cover', 'PUT'], ['/api/photos/' + photo.id, 'DELETE'],
    ]) expect((await owner.request(path, method, undefined, cookie)).status).toBe(401);
  }
  for (const [path, method] of [['/api/photos/' + photo.id + '/cover', 'PUT'], ['/api/photos/' + photo.id, 'DELETE']]) expect((await fetch(harness.base + path, { method, headers: { cookie: owner.cookie, origin: 'https://untrusted.example' } })).status).toBe(403);
  expect((await fetch(harness.minioOrigin + '/' + harness.bucket + '/' + photo.original_key)).status).toBe(403);
  for (const id of ['bad-id', randomUUID()]) {
    expect((await owner.request('/api/photos/' + id + '/original')).status).toBe(404);
    expect((await owner.request('/api/photos/' + id + '/cover', 'PUT')).status).toBe(404);
    expect((await owner.request('/api/photos/' + id, 'DELETE')).status).toBe(404);
    expect((await upload(gpsImage, {}, owner.cookie, id)).status).toBe(404);
  }
  expect(await updates()).toEqual(before);
  expect(await objectKeys()).toEqual(keys);
});

test('cannot cross households for journal entries, plant updates or stored objects', async () => {
  const fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  try {
    // Invitations have no API yet. SQL supplies membership only.
    await fixtureDb.query('DROP INDEX households_singleton');
    const household = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test photo household') RETURNING id");
    await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-photo-other', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  } finally { await fixtureDb.end(); }
  const other = await harness.login('synthetic-photo-other');
  const photo = (await updates())[0];
  const before = await updates();
  const keys = await objectKeys();
  for (const [path, method] of [
    ['/api/plants/' + plantId + '/photos', 'GET'], ['/api/photos/' + photo.id + '/original', 'GET'],
    ['/api/photos/' + photo.id + '/thumb', 'GET'], ['/api/photos/' + photo.id + '/cover', 'PUT'],
    ['/api/photos/' + photo.id, 'DELETE'],
  ]) expect((await other.request(path, method)).status).toBe(404);
  expect((await upload(gpsImage, {}, other.cookie)).status).toBe(404);
  expect(await (await other.request('/api/journal')).json()).toEqual([]);
  const site = await (await other.request('/api/sites', 'POST', { name: 'Test foreign photo room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await other.request('/api/species/search?q=Testoria%20lucida')).json();
  const plant = await (await other.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json();
  const foreign = await (await upload(gpsImage, {}, other.cookie, plant.id)).json();
  expect(foreign.original_key.split('/')[1]).not.toBe(photo.original_key.split('/')[1]);
  expect((await owner.request('/api/photos/' + foreign.id + '/original')).status).toBe(404);
  expect(await updates()).toEqual(before);
  expect((await (await owner.request('/api/journal')).json()).map((update: { id: string }) => update.id)).not.toContain(foreign.id);
  expect(await objectKeys()).toEqual([...keys, foreign.original_key, foreign.thumb_key].sort());
});

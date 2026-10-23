import { beforeAll, expect, test } from 'vitest';
import { randomUUID } from 'node:crypto';
import sharp from 'sharp';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ testClock: true, photos: true });
let owner: Awaited<ReturnType<typeof harness.login>>;
let image: Buffer;
const conditions = { name: 'Test lifecycle room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false };

beforeAll(async () => {
  owner = await harness.login();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-01T12:00:00Z' });
  image = await sharp({ create: { width: 20, height: 20, channels: 3, background: '#408040' } }).jpeg().toBuffer();
});
async function add(nickname = 'Test lifecycle sprout') {
  const site = await (await owner.request('/api/sites', 'POST', conditions)).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  const response = await owner.request('/api/plants', 'POST', { ...fixturePlant, nickname, site_id: site.id, search_id: search.search_id, gbif_key: 910001 });
  expect(response.status).toBe(201);
  return { plant: await response.json(), site };
}
async function upload(plant: string) {
  const form = new FormData();
  form.set('photo', new Blob([new Uint8Array(image)], { type: 'image/jpeg' }), 'synthetic.jpg');
  form.set('note', 'Test retained photo');
  const response = await fetch(harness.base + '/api/plants/' + plant + '/photos', { method: 'POST', headers: { cookie: owner.cookie, origin: harness.base }, body: form });
  expect(response.status).toBe(201);
  return response.json();
}
const read = async (path: string) => (await owner.request(path)).json();

test('full delete requires the current name, removes history, intervals and both objects, and leaves other plants intact', async () => {
  const { plant } = await add('Test deleted sprout');
  const { plant: survivor } = await add('Test surviving sprout');
  const path = '/api/plants/' + plant.id;
  await owner.request(path + '/tasks/water/complete', 'POST', { request_id: randomUUID() });
  await owner.request(path + '/care-settings', 'PUT', { customIntervals: { water: 4 } });
  const photos = [await upload(plant.id), await upload(plant.id)];
  const keptPhoto = await upload(survivor.id);
  const keptPlant = await read('/api/plants/' + survivor.id);
  for (const input of [{}, { confirm_name: 'wrong' }, { confirmed: true }]) expect((await owner.request(path, 'DELETE', input)).status).toBe(422);
  expect(await read(path + '/photos')).toHaveLength(2);
  expect((await owner.request(path, 'DELETE', { confirm_name: plant.display_name })).status).toBe(204);
  for (const suffix of ['', '/history', '/care-settings', '/photos']) expect((await owner.request(path + suffix)).status).toBe(404);
  const journal = await read('/api/journal');
  for (const photo of photos) {
    expect(journal).not.toContainEqual(expect.objectContaining({ id: photo.id }));
    for (const key of [photo.original_key, photo.thumb_key]) await expect(harness.storage.statObject(harness.bucket, key)).rejects.toMatchObject({ code: 'NotFound' });
  }
  expect(await read('/api/plants/' + survivor.id)).toEqual(keptPlant);
  expect((await owner.request('/api/photos/' + keptPhoto.id + '/original')).status).toBe(200);
  await expect(harness.storage.statObject(harness.bucket, keptPhoto.thumb_key)).resolves.toMatchObject({ size: expect.any(Number) });
});

test('an object-delete failure still commits full deletion and logs each cleanup key without credentials', async () => {
  const { plant } = await add('Test failed cleanup sprout');
  const photo = await upload(plant.id);
  const start = harness.appOutput.length;
  harness.failPhotoDeletes(true);
  try { expect((await owner.request('/api/plants/' + plant.id, 'DELETE', { confirm_name: plant.display_name })).status).toBe(204); }
  finally { harness.failPhotoDeletes(false); }
  expect((await owner.request('/api/plants/' + plant.id)).status).toBe(404);
  expect(await read('/api/journal')).not.toContainEqual(expect.objectContaining({ id: photo.id }));
  for (const key of [photo.original_key, photo.thumb_key]) {
    await expect(harness.storage.statObject(harness.bucket, key)).resolves.toMatchObject({ size: expect.any(Number) });
    await expect.poll(() => harness.appOutput.slice(start)).toContain(key);
  }
  expect(harness.appOutput.slice(start)).toContain('Deleted plant photo cleanup failed');
  expect(harness.appOutput.slice(start)).not.toMatch(/synthetic-minio-password|AccessDenied|Authorization/);
  // The logged keys are sufficient for an operator to clean the failed objects up later.
  await harness.storage.removeObjects(harness.bucket, [photo.original_key, photo.thumb_key]);
});

test('species changes require a household search and confirmation, keep every care event and recompute from catalogue overrides', async () => {
  const { plant } = await add('Test corrected sprout');
  const path = '/api/plants/' + plant.id;
  const tasks = ['water', 'fertilize', 'clean_leaves', 'remove_dead', 'cut_back', 'repot'];
  for (const type of tasks) expect((await owner.request(path + '/tasks/' + type + '/complete', 'POST', { request_id: randomUUID() })).status).toBe(200);
  const history = await read(path + '/history');
  const photo = await upload(plant.id);
  await owner.request(path + '/care-settings', 'PUT', { customIntervals: { clean_leaves: 11 } });
  harness.setGbif({ usageKey: 910019, scientificName: 'Testoria correcta Inventor', canonicalName: 'Testoria correcta', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'FUZZY', confidence: 80 });
  const search = await read('/api/species/search?q=Correctia');
  expect((await owner.request(path + '/species', 'POST', { search_id: search.search_id, gbif_key: 999999, confirmed: true })).status).toBe(422);
  for (const confirmed of [undefined, false, 'true']) expect((await owner.request(path + '/species', 'POST', { search_id: search.search_id, gbif_key: 910019, confirmed })).status).toBe(422);
  // A second plant makes the new species available to the household's catalogue editor.
  expect((await owner.request('/api/plants', 'POST', { ...fixturePlant, nickname: 'Test correct species reference', site_id: plant.site_id, search_id: search.search_id, gbif_key: 910019, confirmed: true })).status).toBe(201);
  for (const [key, values] of [
    [910001, { wateringIntervalDays: 10, fertilizingIntervalDays: 15, pruningMonths: [3] }],
    [910019, { wateringIntervalDays: 20, fertilizingIntervalDays: 25, pruningMonths: [9] }],
  ] as const) {
    for (const [field, value] of Object.entries(values)) expect((await owner.request('/api/species/' + key + '/care/overrides/' + field, 'PUT', { value })).status).toBe(200);
  }
  const old = await read(path + '/tasks');
  expect(old.water).toMatchObject({ intervalDays: 10, dueOn: '2026-06-11' });
  expect(old.fertilize.dueOn).toBe('2026-06-16');
  expect(old.cut_back.dueOn).toBe('2027-03-01');
  expect((await owner.request(path + '/species', 'POST', { search_id: search.search_id, gbif_key: 910019, confirmed: true })).status).toBe(200);
  expect(await read(path)).toMatchObject({ gbif_key: 910019, scientific_name: 'Testoria correcta Inventor' });
  expect(await read(path + '/history')).toEqual(history);
  const next = await read(path + '/tasks');
  expect(next.water).toMatchObject({ intervalDays: 20, dueOn: '2026-06-21' });
  expect(next.fertilize.dueOn).toBe('2026-06-26');
  expect(next.cut_back.dueOn).toBe('2027-09-01');
  expect(next.clean_leaves.dueOn).toBe('2026-06-12');
  expect(await read(path + '/care-settings')).toEqual({ customIntervals: { clean_leaves: 11 } });
  expect(await read(path + '/photos')).toContainEqual(expect.objectContaining({ id: photo.id }));
  expect(next.remove_dead.dueOn).toBe('2026-06-15');
  expect(next.repot.dueOn).toBe('2028-05-31');
  expect(next.water.basedOn).toContainEqual(expect.objectContaining({ input: 'wateringIntervalDays', value: 20, source: 'override' }));
  expect(await read(path + '/species-changes')).toEqual([expect.objectContaining({ previous_gbif_key: 910001, previous_name: 'Testoria lucida Inventor', next_gbif_key: 910019, next_name: 'Testoria correcta Inventor', changed_on: '2026-06-01' })]);
  const page = await (await owner.request('/plants/' + plant.id)).text();
  expect(page).toContain('Species changed from Testoria lucida Inventor to Testoria correcta Inventor');
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 300001 });
  expect((await owner.request(path + '/species', 'POST', { search_id: search.search_id, gbif_key: 910019, confirmed: true })).status).toBe(422);
  harness.setGbif({ usageKey: 910001, scientificName: 'Testoria lucida Inventor', canonicalName: 'Testoria lucida', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  await owner.request(path + '/graveyard', 'POST');
  expect((await owner.request(path, 'DELETE', { confirm_name: plant.display_name })).status).toBe(204);
  expect((await owner.request(path + '/species-changes')).status).toBe(404);
  expect(await read('/api/plants?status=graveyard')).not.toContainEqual(expect.objectContaining({ id: plant.id }));
  for (const key of [photo.original_key, photo.thumb_key]) await expect(harness.storage.statObject(harness.bucket, key)).rejects.toMatchObject({ code: 'NotFound' });
});

test('correcting to a new species queues catalogue care and names, and deleting its last plant keeps species names', async () => {
  const { plant } = await add('Test new species sprout');
  harness.setGbif({ usageKey: 910029, scientificName: 'Testoria nova Inventor', canonicalName: 'Testoria nova', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  harness.setNames('gbif', { results: [{ vernacularName: 'Test lantern', language: 'eng' }], endOfRecords: true });
  const search = await read('/api/species/search?q=Testoria%20nova');
  const path = '/api/plants/' + plant.id;
  expect((await owner.request(path + '/species', 'POST', { search_id: search.search_id, gbif_key: 910029 })).status).toBe(200);
  expect((await read('/api/species/910029/care')).fetches).toContainEqual(expect.objectContaining({ source: 'perenual', status: 'pending', attempts: 0 }));
  expect((await read('/api/species/910029')).names).toContainEqual(expect.objectContaining({ name: 'Testoria nova', language: 'la', source: 'gbif' }));
  await expect.poll(async () => (await read('/api/species/910029')).names).toContainEqual(expect.objectContaining({ name: 'Test lantern', language: 'en', source: 'gbif' }));
  expect((await owner.request(path, 'DELETE', { confirm_name: plant.display_name })).status).toBe(204);
  expect((await read('/api/species/910029')).names).toContainEqual(expect.objectContaining({ name: 'Testoria nova', language: 'la', source: 'gbif' }));
  harness.setNames('gbif', { results: [], endOfRecords: true });
  harness.setGbif({ usageKey: 910001, scientificName: 'Testoria lucida Inventor', canonicalName: 'Testoria lucida', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
});

test('graveyard plants do not block site deletion; a detached plant needs a household site to restore', async () => {
  const { plant, site } = await add('Test detached sprout');
  const path = '/api/plants/' + plant.id;
  await owner.request(path + '/graveyard', 'POST');
  const search = await read('/api/species/search?q=Testoria%20lucida');
  const active = await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json();
  const refused = await owner.request('/api/sites/' + site.id, 'DELETE');
  expect(refused.status).toBe(409);
  expect((await refused.json()).message).toContain('the 1 plant');
  expect(await read(path)).toMatchObject({ site_id: site.id });
  await owner.request('/api/plants/' + active.id + '/graveyard', 'POST');
  expect((await owner.request('/api/sites/' + site.id, 'DELETE')).status).toBe(204);
  expect(await read(path)).toMatchObject({ status: 'graveyard', site_id: null, site_name: null });
  expect((await owner.request('/plants/' + plant.id)).status).toBe(200);
  expect((await owner.request(path, 'PUT', { ...await read(path), nickname: 'Test edited detached sprout' })).status).toBe(200);
  expect((await owner.request(path + '/restore', 'POST', {})).status).toBe(422);
  for (const site_id of [site.id, 'invalid', randomUUID()]) expect((await owner.request(path + '/restore', 'POST', { site_id })).status).toBe(422);
  const replacement = await (await owner.request('/api/sites', 'POST', { ...conditions, name: 'Test replacement room' })).json();
  expect((await owner.request(path + '/restore', 'POST', { site_id: replacement.id })).status).toBe(200);
  expect(await read(path)).toMatchObject({ status: 'active', site_id: replacement.id, graveyard_on: null });
  expect(await read('/api/plants?site_id=' + replacement.id)).toContainEqual(expect.objectContaining({ id: plant.id }));
  expect((await owner.request(path + '/restore', 'POST', {})).status).toBe(409);
  expect((await owner.request(path, 'PUT', { ...await read(path), site_id: null })).status).toBe(422);
});

test('refuses anonymous, cross-origin and cross-household lifecycle operations and foreign search records', async () => {
  const { plant, site } = await add('Test protected lifecycle sprout');
  const path = '/api/plants/' + plant.id;
  const search = await read('/api/species/search?q=Testoria%20lucida');
  const speciesInput = { search_id: search.search_id, gbif_key: 910001, confirmed: true };
  const operations = [
    [path + '/graveyard', 'POST', {}], [path + '/restore', 'POST', { site_id: site.id }],
    [path + '/species', 'POST', speciesInput], [path, 'DELETE', { confirm_name: plant.display_name }],
    [path + '/species-changes', 'GET', undefined], ['/api/plants?status=graveyard', 'GET', undefined],
  ] as const;
  for (const [endpoint, method, body] of operations) {
    for (const cookie of ['', 'fytorium_session=invalid']) expect((await owner.request(endpoint, method, body, cookie)).status).toBe(401);
    if (method !== 'GET') expect((await fetch(harness.base + endpoint, { method, headers: { cookie: owner.cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: JSON.stringify(body) })).status).toBe(403);
  }
  for (const page of ['/plants/graveyard', '/plants/' + plant.id + '/species']) {
    const response = await owner.request(page, 'GET', undefined, '');
    expect(response.status).toBe(303);
    expect(response.headers.get('location')).toBe('/auth/login');
  }
  const fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  try {
    // Membership has no invitation API on this base. All lifecycle operations use HTTP.
    await fixtureDb.query('DROP INDEX households_singleton');
    const household = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test lifecycle other household') RETURNING id");
    await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-lifecycle-other', 'owner' FROM members LIMIT 1", [household.rows[0].id]);
  } finally { await fixtureDb.end(); }
  const other = await harness.login('synthetic-lifecycle-other');
  for (const [endpoint, method, body] of operations.slice(0, -1)) expect((await other.request(endpoint, method, body)).status).toBe(404);
  const foreignSite = await (await other.request('/api/sites', 'POST', { ...conditions, name: 'Test foreign lifecycle room' })).json();
  const foreignSearch = await (await other.request('/api/species/search?q=Testoria%20lucida')).json();
  expect((await owner.request(path + '/species', 'POST', { ...speciesInput, search_id: foreignSearch.search_id })).status).toBe(422);
  for (const suffix of ['/species', '/restore']) {
    for (const body of [null, [], 'invalid']) expect((await owner.request(path + suffix, 'POST', body)).status).toBe(400);
  }
  for (const id of ['bad-id', randomUUID()]) {
    for (const [suffix, method, body] of [['/graveyard', 'POST', {}], ['/restore', 'POST', {}], ['/species', 'POST', speciesInput], ['', 'DELETE', { confirm_name: plant.display_name }]] as const) expect((await owner.request('/api/plants/' + id + suffix, method, body)).status).toBe(404);
  }
  expect(await read(path)).toMatchObject({ status: 'active', gbif_key: 910001 });
  await owner.request(path + '/graveyard', 'POST');
  expect((await owner.request(path + '/restore', 'POST', { site_id: foreignSite.id })).status).toBe(422);
  expect(await (await other.request('/api/plants?status=graveyard')).json()).toEqual([]);
  expect((await other.request('/api/sites/' + site.id, 'DELETE')).status).toBe(404);
  expect(await read(path)).toMatchObject({ status: 'graveyard', site_id: site.id });
});

test('graveyard hides tasks, active lists and counts, retains history and photos, and restores', async () => {
  const { plant, site } = await add();
  const path = '/api/plants/' + plant.id;
  const done = await (await owner.request(path + '/tasks/clean_leaves/complete', 'POST', { request_id: randomUUID() })).json();
  const photo = await upload(plant.id);
  await owner.request('/api/photos/' + photo.id + '/cover', 'PUT');
  const before = await read(path + '/history');
  const total = (await read('/api/plants?count=true')).count;
  expect((await read('/api/tasks?view=today')).groups.water).toContainEqual(expect.objectContaining({ plant_id: plant.id }));
  expect(await read('/api/plants')).toContainEqual(expect.objectContaining({ id: plant.id }));
  expect((await owner.request(path + '/graveyard', 'POST')).status).toBe(200);
  expect(await read(path)).toMatchObject({ status: 'graveyard', graveyard_on: '2026-06-01', cover_photo_id: photo.id });
  await owner.request('/__test/clock', 'POST', { action: 'advance', milliseconds: 86400000 });
  await owner.request(path + '/graveyard', 'POST');
  expect(await read(path)).toMatchObject({ graveyard_on: '2026-06-01' });
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-01T12:00:00Z' });
  expect(await read('/api/plants?status=graveyard')).toContainEqual(expect.objectContaining({ id: plant.id, graveyard_on: '2026-06-01' }));
  expect(await read('/api/plants')).not.toContainEqual(expect.objectContaining({ id: plant.id }));
  expect(await read('/api/plants?site_id=' + site.id)).toEqual([]);
  expect(await read('/api/plants?count=true')).toEqual({ count: total - 1 });
  expect(await read('/api/plants?count=true&site_id=' + site.id)).toEqual({ count: 0 });
  expect(await read('/api/sites')).toContainEqual(expect.objectContaining({ id: site.id, plant_count: 0 }));
  const today = await read('/api/tasks?view=today');
  for (const tasks of Object.values(today.groups) as { plant_id: string }[][]) expect(tasks.map(task => task.plant_id)).not.toContain(plant.id);
  expect(await read(path + '/tasks')).toEqual({});
  expect((await owner.request(path + '/tasks/water/complete', 'POST', { request_id: randomUUID() })).status).toBe(409);
  expect((await owner.request('/api/care-events/' + done.event.id + '/undo', 'POST')).status).toBe(409);
  expect(await read(path + '/history')).toEqual(before.map((event: Record<string, unknown>) => ({ ...event, can_undo: false })));
  expect(await read(path + '/photos')).toContainEqual(expect.objectContaining({ id: photo.id }));
  expect((await owner.request('/api/photos/' + photo.id + '/original')).status).toBe(200);
  expect(await (await owner.request('/plants/' + plant.id)).text()).toContain('In the graveyard since 2026-06-01');
  expect((await owner.request(path + '/restore', 'POST', {})).status).toBe(200);
  expect(await read(path)).toMatchObject({ status: 'active', graveyard_on: null, site_id: site.id });
  expect(await read(path + '/tasks')).toHaveProperty('water');
  expect(await read(path + '/history')).toEqual(before);
  expect((await owner.request('/api/sites/' + site.id, 'DELETE')).status).toBe(409);
});

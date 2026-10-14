import { beforeAll, expect, test } from 'vitest';
import { createApiHarness } from './harness';
import sharp from 'sharp';
import pg from 'pg';
import { randomUUID } from 'node:crypto';
import { createImageHost } from './planta-images';
import { createFakePlanta, syntheticPlant } from './planta-fake';

const fake = createFakePlanta();
const imageHost = createImageHost(fake.env);
const harness = createApiHarness({ testClock: true, photos: true, appEnv: fake.env, openPlantbook: () => ({ status: 200, body: { results: [] } }) });
let owner: Awaited<ReturnType<typeof harness.login>>;
const endpoint = '/api/import/planta';
beforeAll(async () => {
  owner = await harness.login();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-10T12:00:00Z' });
});

test('exchanges a one-time code, pages plants and retains only mapped staging without exposing credentials', async () => {
  const response = await owner.request(endpoint, 'POST', { code: fake.code });
  expect(response.status).toBe(200);
  const output = await response.text();
  const rows = JSON.parse(output);
  expect(rows).toHaveLength(2);
  expect(rows[0]).toMatchObject({ planta_plant_id: 'synthetic-planta-20-a', status: 'confirmed', mapped: { nickname: 'Test import lantern', site_name: 'Test import alcove', pot_type: 'terracotta', pot_size_cm: 18, soil_type: 'cactus', care: { water: '2026-06-01T12:00:00.000Z' } }, selected: { gbif_key: 910001 } });
  expect(fake.calls.map(call => call.path)).toEqual(['/v1/auth/authorize', '/v1/addedPlants', '/v1/addedPlants?cursor=synthetic-next']);
  expect(await (await owner.request(endpoint)).json()).toEqual(rows);
  for (const secret of [fake.code, fake.access, fake.refresh, 'ignoredPrivateField', 'Synthetic field must not be retained']) {
    expect(output).not.toContain(secret);
    expect(harness.appOutput).not.toContain(secret);
  }
});

test('requires explicit fuzzy confirmation, accepts an alternative and persists skipped plants', async () => {
  harness.setGbif({ usageKey: 910020, scientificName: 'Testoria incerta Inventor', canonicalName: 'Testoria incerta', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'FUZZY', confidence: 88,
    alternatives: [{ usageKey: 910021, scientificName: 'Testoria altera Inventor', canonicalName: 'Testoria altera', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 }] });
  // Use a name not already stored by an earlier import.
  const plants = [importPlant('synthetic-fuzzy-a'), importPlant('synthetic-fuzzy-b')];
  fake.setPlants(plants);
  const rows = await (await owner.request(endpoint, 'POST', { code: fake.code })).json();
  expect(rows.map((row: { status: string }) => row.status)).toEqual(['review', 'review']);
  expect(rows).toEqual(expect.arrayContaining([expect.objectContaining({ can_commit: false, can_review: true, cover_status: 'none' })]));
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(422);
  expect((await owner.request(endpoint + '/' + rows[0].id, 'PUT', { gbif_key: 910021 })).status).toBe(422);
  expect((await owner.request(endpoint + '/' + rows[0].id, 'PUT', { gbif_key: 42, confirmed: true })).status).toBe(422);
  expect((await owner.request(endpoint + '/' + rows[0].id, 'PUT', { gbif_key: 910021, confirmed: true })).status).toBe(200);
  expect((await owner.request(endpoint + '/' + rows[1].id, 'PUT', { skip: true })).status).toBe(200);
  expect(await (await owner.request(endpoint)).json()).toEqual(expect.arrayContaining([expect.objectContaining({ can_commit: true, can_review: true })]));
  const committed = await owner.request(endpoint + '/commit', 'POST', {});
  expect(committed.status).toBe(200);
  expect(await committed.json()).toEqual([expect.objectContaining({ planta_plant_id: 'synthetic-fuzzy-a', status: 'committed', can_commit: false, can_review: false, selected: expect.objectContaining({ gbif_key: 910021 }) }), expect.objectContaining({ status: 'skipped', can_commit: false, can_review: true })]);
  const imported = await (await owner.request('/api/plants')).json();
  expect(imported).toHaveLength(1);
  expect(imported[0]).toMatchObject({ gbif_key: 910021, nickname: 'Test import lantern', cultivar: 'Invented stripe', site_name: 'Test import alcove', pot_type: 'terracotta', pot_size_cm: 18, soil_type: 'cactus' });
  const history = await (await owner.request('/api/plants/' + imported[0].id + '/history')).json();
  expect(history).toHaveLength(5);
  expect(history).toContainEqual(expect.objectContaining({ task_type: 'water', kind: 'completed', source: 'planta', occurred_on: '2026-06-01' }));
  expect((await owner.request('/api/plants/' + imported[0].id + '/care-settings', 'PUT', { misting_enabled: true, customIntervals: { water: 10 } })).status).toBe(200);
  const tasks = await (await owner.request('/api/plants/' + imported[0].id + '/tasks')).json();
  expect(tasks.water).toMatchObject({ intervalDays: 10, dueOn: '2026-06-11' });
  expect(tasks).toMatchObject({ fertilize: { dueOn: '2026-06-14' }, mist: { dueOn: '2026-06-05' }, clean_leaves: { dueOn: '2026-06-19' }, repot: { dueOn: '2027-06-01' } });
  expect(tasks.water.basedOn).toContainEqual(expect.objectContaining({ input: 'history.water.lastCompletedOn', value: '2026-06-01' }));
  expect((await (await owner.request('/api/sites')).json())[0]).toMatchObject({ name: 'Test import alcove', imported_conditions_incomplete: true, location: 'indoor', light: 'part_sun', humidity: 'normal' });
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(await (await owner.request('/api/plants')).json()).toHaveLength(1);
});

test('a fresh fetch updates Planta fields including owner edits, but preserves local settings and IDs', async () => {
  const plant = (await (await owner.request('/api/plants')).json())[0];
  const path = '/api/plants/' + plant.id;
  expect((await owner.request(path, 'PUT', { ...plant, nickname: 'Test locally renamed lantern', pot_size_cm: 30, growth_stage: 'mature', health: 'good', near_heater: true })).status).toBe(200);
  const updated = importPlant('synthetic-fuzzy-a');
  updated.names.custom = 'Test renamed by Planta';
  updated.environment.pot.size = 22;
  updated.actions.watering.completed.date = '2026-06-04T12:00:00Z';
  fake.setPlants([updated]);
  const rows = await (await owner.request(endpoint, 'POST', { code: fake.code })).json();
  // A confirmed alternative has become an exact stored scientific name.
  const search = await (await owner.request('/api/species/search?q=Testoria%20altera')).json();
  expect((await owner.request(endpoint + '/' + rows[0].id, 'PUT', { search_id: search.search_id, gbif_key: 910021 })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(await (await owner.request('/api/plants')).json()).toHaveLength(1);
  expect(await (await owner.request(path)).json()).toMatchObject({ id: plant.id, nickname: 'Test renamed by Planta', pot_size_cm: 22, growth_stage: 'mature', health: 'good', near_heater: true });
  const sites = await (await owner.request('/api/sites')).json();
  expect(sites).toHaveLength(1);
  expect((await owner.request('/api/sites/' + sites[0].id, 'PUT', { ...sites[0], humidity: 'humid' })).status).toBe(200);
  expect((await (await owner.request('/api/sites/' + sites[0].id)).json()).imported_conditions_incomplete).toBe(false);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  const staged = await (await owner.request(endpoint)).json();
  await owner.request(endpoint + '/' + staged[0].id, 'PUT', { search_id: search.search_id, gbif_key: 910021 });
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(await (await owner.request('/api/sites/' + sites[0].id)).json()).toMatchObject({ humidity: 'humid', imported_conditions_incomplete: false });
  const events = await (await owner.request(path + '/history')).json();
  expect(events).toHaveLength(5);
  expect(events).toContainEqual(expect.objectContaining({ source: 'planta', task_type: 'water', occurred_on: '2026-06-04' }));
  expect(await (await owner.request(path + '/tasks')).json()).toMatchObject({ water: { intervalDays: 10, dueOn: '2026-06-14' } });
});

test('staging and reviewed selections survive restart without needing code or tokens again', async () => {
  const before = await (await owner.request(endpoint)).json();
  const calls = fake.calls.length;
  await harness.restartApp();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-10T12:00:00Z' });
  expect(await (await owner.request(endpoint)).json()).toEqual(before);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(fake.calls).toHaveLength(calls);
});

test('future or invalid dates refuse the fetch and leave the prior staging intact; unknown pot values fall back', async () => {
  const before = await (await owner.request(endpoint)).json();
  for (const value of ['2026-06-11T12:00:00Z', 'bad-date', '2026-02-30', '2026-02-30T12:00:00Z']) {
    const plant = syntheticPlant('synthetic-future');
    plant.actions.watering.completed.date = value;
    fake.setPlants([plant]);
    expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(422);
    expect(await (await owner.request(endpoint)).json()).toEqual(before);
  }
  for (const type of ['invented-pot', 'toString', 'constructor', 'none']) {
    const unknown = syntheticPlant('synthetic-unknown-pot');
    unknown.environment.pot = { type, soil: type, size: -4 };
    fake.setPlants([unknown]);
    const response = await owner.request(endpoint, 'POST', { code: fake.code });
    expect(response.status).toBe(200);
    expect((await response.json())[0].mapped).toMatchObject({ pot_type: 'other', soil_type: 'other', pot_size_cm: null });
  }
  const mixedCase = syntheticPlant('synthetic-case-pot');
  mixedCase.environment.pot = { type: 'potTerracotta', soil: 'CactusSoil', size: 20 };
  fake.setPlants([mixedCase]);
  expect((await (await owner.request(endpoint, 'POST', { code: fake.code })).json())[0].mapped).toMatchObject({ pot_type: 'terracotta', soil_type: 'cactus', pot_size_cm: 20 });
});

test('imports without a failed cover, retains the reason across restart and retries on a fresh fetch', async () => {
  harness.setGbif({ usageKey: 910001, scientificName: 'Testoria lucida Inventor', canonicalName: 'Testoria lucida', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const a = syntheticPlant('synthetic-photo-a', 'Test photo lantern');
  const b = syntheticPlant('synthetic-photo-b', 'Test photo glow');
  b.image.url = imageHost.url;
  fake.setPlants([a, b]);
  const stagingResponse = await owner.request(endpoint, 'POST', { code: fake.code });
  const stagedOutput = await stagingResponse.text();
  expect(stagedOutput).not.toContain(imageHost.url);
  expect(stagedOutput).not.toContain('cover_url');
  const rows = JSON.parse(stagedOutput);
  expect(rows[1].mapped.has_cover).toBe(true);
  expect(rows[1].cover_status).toBe('pending');
  await imageHost.setStatus(503);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  const failed = await (await owner.request(endpoint)).json();
  expect(failed).toEqual([expect.objectContaining({ id: rows[0].id, status: 'committed' }), expect.objectContaining({ id: rows[1].id, status: 'committed', cover_error: expect.stringContaining('download failed') })]);
  expect(failed[1].cover_status).toBe('failed');
  const withoutCover = await (await owner.request('/api/plants/' + failed[1].plant_id)).json();
  expect(withoutCover.cover_photo_id).toBeNull();
  expect(await (await owner.request('/api/plants/' + failed[1].plant_id + '/photos')).json()).toEqual([]);
  expect(await (await owner.request('/import')).text()).toContain('Cover not imported');
  await harness.restartApp();
  await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-10T12:00:00Z' });
  expect(await (await owner.request(endpoint)).json()).toEqual(failed);
  await imageHost.setStatus(200);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  const plants = await (await owner.request('/api/plants')).json();
  const plant = plants.find((plant: { nickname: string }) => plant.nickname === 'Test photo glow');
  expect(plant.cover_photo_id).toEqual(expect.any(String));
  expect((await (await owner.request(endpoint)).json())[1].cover_status).toBe('imported');
  const photos = await (await owner.request('/api/plants/' + plant.id + '/photos')).json();
  expect(photos).toHaveLength(1);
  expect(photos[0]).toMatchObject({ is_cover: true, taken_on: '2026-06-10' });
  for (const variant of ['original', 'thumb']) {
    const response = await owner.request('/api/photos/' + photos[0].id + '/' + variant);
    expect(response.status).toBe(200);
    const metadata = await sharp(Buffer.from(await response.arrayBuffer())).metadata();
    expect(metadata.format).toBe('jpeg');
    expect(metadata.exif).toBeUndefined();
    expect(metadata.orientation).toBeUndefined();
    expect(metadata.icc).toBeUndefined();
    if (variant === 'original') expect(metadata).toMatchObject({ width: 800, height: 600 });
    else expect(Math.max(metadata.width!, metadata.height!)).toBeLessThanOrEqual(400);
  }
  const calls = await imageHost.calls();
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(await imageHost.calls()).toBe(calls);
  expect(await (await owner.request('/api/plants/' + plant.id + '/photos')).json()).toEqual(photos);
  expect((await (await owner.request('/api/plants')).json()).filter((plant: { nickname: string }) => plant.nickname.startsWith('Test photo'))).toHaveLength(2);
});

test('a committed row never promises a cover download after its imported plant is deleted', async () => {
  const imported = syntheticPlant('synthetic-deleted-cover', 'Test deleted imported cover');
  imported.image.url = imageHost.url;
  fake.setPlants([imported]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  const committed = await owner.request(endpoint + '/commit', 'POST', {});
  expect(committed.status).toBe(200);
  const row = (await committed.json())[0];
  expect(row).toMatchObject({ status: 'committed', cover_status: 'imported' });
  expect((await owner.request('/api/plants/' + row.plant_id, 'DELETE', { confirm_name: imported.names.custom })).status).toBe(204);
  const afterDelete = await (await owner.request(endpoint)).json();
  expect(afterDelete).toEqual([expect.objectContaining({ id: row.id, status: 'committed', plant_id: null,
    cover_status: 'skipped', can_commit: false, can_review: false })]);
  const calls = await imageHost.calls();
  expect((await (await owner.request(endpoint + '/commit', 'POST', {})).json())[0].cover_status).toBe('skipped');
  expect(await imageHost.calls()).toBe(calls);
});

test('a skipped row with a Planta cover reports that its cover is skipped', async () => {
  const imported = syntheticPlant('synthetic-skipped-cover');
  imported.image.url = imageHost.url;
  fake.setPlants([imported]);
  const staged = await owner.request(endpoint, 'POST', { code: fake.code });
  expect(staged.status).toBe(200);
  const row = (await staged.json())[0];
  expect(row.cover_status).toBe('pending');
  const skipped = await owner.request(endpoint + '/' + row.id, 'PUT', { skip: true });
  expect(skipped.status).toBe(200);
  expect(await skipped.json()).toMatchObject({ status: 'skipped', cover_status: 'skipped', can_review: true, can_commit: false });
  expect((await (await owner.request(endpoint)).json())[0].cover_status).toBe('skipped');
});

test('a cover decode failure imports the plant and explains why there is no cover', async () => {
  const plant = syntheticPlant('synthetic-decode-cover');
  plant.image.url = imageHost.url.replace('cover.jpg', 'invalid.jpg');
  await imageHost.stub(200, '/invalid.jpg', {}, 'synthetic-not-an-image');
  fake.setPlants([plant]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  const response = await owner.request(endpoint + '/commit', 'POST', {});
  expect(response.status).toBe(200);
  const row = (await response.json())[0];
  expect(row).toMatchObject({ status: 'committed', cover_error: 'the image could not be read.' });
  expect(await (await owner.request('/api/plants/' + row.plant_id + '/photos')).json()).toEqual([]);
});

test('a thumbnail failure still imports the plant and records a cover error', async () => {
  const plant = syntheticPlant('synthetic-thumbnail-cover');
  plant.image.url = imageHost.url;
  fake.setPlants([plant]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  await harness.restartApp({ NODE_OPTIONS: '--import ./tests/api/fail-thumbnail.mjs' });
  try {
    await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-10T12:00:00Z' });
    const response = await owner.request(endpoint + '/commit', 'POST', {});
    expect(response.status).toBe(200);
    const row = (await response.json())[0];
    expect(row).toMatchObject({ status: 'committed', cover_error: 'the image could not be read.' });
    expect(await (await owner.request('/import')).text()).toContain('Cover not imported: the image could not be read.');
    expect((await owner.request('/api/plants/' + row.plant_id)).status).toBe(200);
    expect(await (await owner.request('/api/plants/' + row.plant_id + '/photos')).json()).toEqual([]);
  } finally {
    await harness.restartApp();
    await owner.request('/__test/clock', 'POST', { action: 'set', at: '2026-06-10T12:00:00Z' });
  }
});

test('refreshes exactly once after 401, bounds 429 retries and never relays upstream credentials', async () => {
  fake.setPlants([syntheticPlant('synthetic-network-a'), syntheticPlant('synthetic-network-b')]);
  fake.setMode('refresh');
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect(fake.calls.filter(call => call.path === '/v1/auth/refreshToken')).toHaveLength(1);
  expect(fake.calls.filter(call => call.path.endsWith('cursor=synthetic-next'))).toHaveLength(2);
  fake.setMode('rate-limit');
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  const reads = fake.calls.filter(call => call.path === '/v1/addedPlants');
  expect(reads).toHaveLength(2);
  expect(reads[1].at - reads[0].at).toBeGreaterThanOrEqual(90);
  fake.setMode('always-rate-limit');
  const limited = await owner.request(endpoint, 'POST', { code: fake.code });
  expect(limited.status).toBe(503);
  expect((await limited.json()).message).toContain('rate-limiting');
  expect(fake.calls.filter(call => call.path === '/v1/addedPlants')).toHaveLength(3);
  fake.setMode('unauthorized');
  const unauthorized = await owner.request(endpoint, 'POST', { code: fake.code });
  expect(unauthorized.status).toBe(502);
  expect((await unauthorized.json()).message).toContain('authorization');
  expect(fake.calls.filter(call => call.path === '/v1/auth/refreshToken')).toHaveLength(1);
  fake.setMode('normal');
  const rejected = await owner.request(endpoint, 'POST', { code: 'synthetic-invalid-code' });
  expect(rejected.status).toBe(502);
  const output = await rejected.text();
  for (const secret of [fake.code, fake.access, fake.refresh, 'synthetic-invalid-code']) {
    expect(output).not.toContain(secret);
    expect(harness.appOutput).not.toContain(secret);
  }
});

test('refuses HTTP and private, loopback, link-local or mapped-IP cover hosts, never following redirects', async () => {
  const before = await imageHost.calls();
  for (const url of ['http://localhost/cover.jpg', 'https://127.0.0.1/cover.jpg', 'https://localhost/cover.jpg', 'https://10.0.0.4/cover.jpg', 'https://169.254.169.254/cover.jpg', 'https://[::1]/cover.jpg', 'https://[::ffff:127.0.0.1]/cover.jpg']) {
    const plant = syntheticPlant('synthetic-ssrf');
    plant.image.url = url;
    fake.setPlants([plant]);
    expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
    const response = await owner.request(endpoint + '/commit', 'POST', {});
    expect(response.status).toBe(200);
    const row = (await response.json())[0];
    expect(row).toMatchObject({ status: 'committed', cover_error: expect.stringMatching(/HTTPS|private or reserved/) });
    expect(await (await owner.request('/api/plants/' + row.plant_id + '/photos')).json()).toEqual([]);
  }
  expect(await imageHost.calls()).toBe(before);
  await imageHost.stub(302, '/redirect.jpg', { Location: 'https://127.0.0.1/cover.jpg' });
  const plant = syntheticPlant('synthetic-redirect');
  plant.image.url = imageHost.url.replace('cover.jpg', 'redirect.jpg');
  fake.setPlants([plant]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  const redirected = await owner.request(endpoint + '/commit', 'POST', {});
  expect(redirected.status).toBe(200);
  expect((await redirected.json())[0].cover_error).toContain('download failed');
  expect(await imageHost.calls()).toBe(before);
  await imageHost.stub(200, '/oversized.jpg', {}, 'x'.repeat(15 * 1024 * 1024 + 1));
  plant.image.url = imageHost.url.replace('cover.jpg', 'oversized.jpg');
  fake.setPlants([plant]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  const oversized = await owner.request(endpoint + '/commit', 'POST', {});
  expect(oversized.status).toBe(200);
  const oversizedRow = (await oversized.json())[0];
  expect(oversizedRow.cover_error).toContain('exceeded 15 MB');
  expect(await (await owner.request('/api/plants/' + oversizedRow.plant_id + '/photos')).json()).toEqual([]);
});

test('bounds Planta and image timeouts, preserving review or importing without a cover', async () => {
  const before = await (await owner.request(endpoint)).json();
  fake.setMode('timeout');
  const start = Date.now();
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(503);
  expect(Date.now() - start).toBeLessThan(9000);
  expect(await (await owner.request(endpoint)).json()).toEqual(before);
  fake.setMode('normal');
  const plant = syntheticPlant('synthetic-image-timeout');
  plant.image.url = imageHost.url.replace('cover.jpg', 'slow.jpg');
  await imageHost.stub(200, '/slow.jpg', {}, undefined, 5500);
  fake.setPlants([plant]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  const imageStart = Date.now();
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(Date.now() - imageStart).toBeLessThan(9000);
  const row = (await (await owner.request(endpoint)).json())[0];
  expect(row).toMatchObject({ status: 'committed', cover_error: expect.stringContaining('download failed') });
  expect(await (await owner.request('/api/plants/' + row.plant_id + '/photos')).json()).toEqual([]);
});

test('re-import preserves standalone import undo, local batches, request IDs, snoozes and skips', async () => {
  const imported = syntheticPlant('synthetic-batch-import', 'Test batch import');
  imported.site.name = 'Test batch import room';
  fake.setPlants([imported]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  const plant = (await (await owner.request('/api/plants')).json()).find((plant: { nickname: string }) => plant.nickname === imported.names.custom);
  const path = '/api/plants/' + plant.id;
  await owner.request(path + '/care-settings', 'PUT', { misting_enabled: true, customIntervals: { water: 10 } });
  const events = await (await owner.request(path + '/history')).json();
  const water = events.find((event: { task_type: string }) => event.task_type === 'water');
  for (const event of events) expect(event).toMatchObject({ kind: 'completed', source: 'planta', batch_id: null, previous_pot: null, request_id: expect.any(String) });
  expect((await owner.request(path + '/tasks/water/complete', 'POST', { request_id: water.request_id })).status).toBe(409);
  const key = randomUUID();
  const batchResponse = await owner.request(path + '/tasks/complete-all', 'POST', { request_id: key });
  expect(batchResponse.status).toBe(200);
  const batch = await batchResponse.json();
  expect(batch.events.length).toBeGreaterThan(0);
  for (const event of batch.events) expect(event).toMatchObject({ batch_id: key, source: 'fytorium', undone_at: null });
  expect((await owner.request('/api/care-events/' + water.id + '/undo', 'POST')).status).toBe(200);
  const undoneWater = (await (await owner.request(path + '/history')).json()).find((event: { id: string }) => event.id === water.id);
  imported.actions.watering.completed.date = '2026-06-09T12:00:00Z';
  fake.setPlants([imported]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  const repeated = await (await owner.request(path + '/history')).json();
  expect(repeated).toHaveLength(events.length + batch.events.length);
  expect(repeated).toContainEqual(expect.objectContaining({ ...undoneWater, occurred_at: '2026-06-09T12:00:00.000Z', occurred_on: '2026-06-09' }));
  for (const event of events.filter((event: { id: string }) => event.id !== water.id)) expect(repeated).toContainEqual(event);
  for (const event of batch.events) expect(repeated).toContainEqual({ ...event, custom_task_name: null });
  expect((await (await owner.request(path + '/tasks/complete-all', 'POST', { request_id: key })).json()).events).toEqual(batch.events);
  expect((await owner.request('/api/care-events/' + batch.events[0].id + '/undo', 'POST')).status).toBe(409);
  expect((await owner.request('/api/task-batches/' + key + '/undo', 'POST')).status).toBe(200);
  const history = await (await owner.request(path + '/history')).json();
  for (const event of batch.events) expect(history).toContainEqual(expect.objectContaining({ id: event.id, batch_id: key, undone_at: expect.any(String) }));
  for (const event of events.filter((event: { id: string }) => event.id !== water.id)) expect(history).toContainEqual(event);
  const snoozed = await (await owner.request(path + '/tasks/water/snooze', 'POST', { request_id: randomUUID() })).json();
  expect(snoozed.task.dueOn).toBe('2026-06-12');
  const skipped = await (await owner.request(path + '/tasks/water/skip', 'POST', { request_id: randomUUID() })).json();
  expect(skipped.task.dueOn).toBe('2026-06-20');
  const before = await (await owner.request(path + '/history')).json();
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(await (await owner.request(path + '/history')).json()).toEqual(before);
  expect((await (await owner.request('/api/care-events/' + skipped.event.id + '/undo', 'POST')).json()).task.dueOn).toBe('2026-06-12');
  expect((await owner.request('/api/care-events/' + snoozed.event.id + '/undo', 'POST')).status).toBe(200);
});

test('import queues both care sources and names without overwriting stored taxonomy on a fresh fetch', async () => {
  harness.setGbif({ usageKey: 910120, scientificName: 'Testoria importata Inventor', canonicalName: 'Testoria importata', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  const imported = syntheticPlant('synthetic-taxonomy', 'Test taxonomy import');
  imported.names.scientific = 'Testoria importata';
  fake.setPlants([imported]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  const species = await (await owner.request('/api/species/910120')).json();
  expect(species).toMatchObject({ scientific_name: 'Testoria importata Inventor', canonical_name: 'Testoria importata', rank: 'SPECIES' });
  expect(species.names).toContainEqual(expect.objectContaining({ name: 'Testoria importata', language: 'la', source: 'gbif' }));
  const care = await (await owner.request('/api/species/910120/care')).json();
  for (const source of ['perenual', 'open_plantbook']) expect(care.fetches).toContainEqual(expect.objectContaining({ source, status: 'pending', attempts: 0 }));
  harness.setGbif({ usageKey: 910120, scientificName: 'Testoria mutata Inventor', canonicalName: 'Testoria mutata', rank: 'SUBSPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
  imported.names.scientific = 'Testoria mutata';
  fake.setPlants([imported]);
  const staged = await (await owner.request(endpoint, 'POST', { code: fake.code })).json();
  expect(staged[0].status).toBe('review');
  expect((await owner.request(endpoint + '/' + staged[0].id, 'PUT', { gbif_key: 910120, confirmed: true, scientific_name: 'Client must not choose taxonomy' })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  const retained = await (await owner.request('/api/species/910120')).json();
  expect(retained).toMatchObject({ scientific_name: 'Testoria importata Inventor', canonical_name: 'Testoria importata', rank: 'SPECIES' });
  expect(retained.names).toContainEqual(expect.objectContaining({ name: 'Testoria importata', language: 'la', source: 'gbif' }));
  expect(retained.names).not.toContainEqual(expect.objectContaining({ name: 'Testoria mutata' }));
  harness.setGbif({ usageKey: 910001, scientificName: 'Testoria lucida Inventor', canonicalName: 'Testoria lucida', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 });
});

test('re-import skips graveyard plants entirely, even after their site was deleted', async () => {
  const imported = syntheticPlant('synthetic-graveyard', 'Test retired import');
  imported.site.name = 'Test retired import room';
  fake.setPlants([imported]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  const plant = (await (await owner.request('/api/plants')).json()).find((plant: { nickname: string }) => plant.nickname === imported.names.custom);
  const path = '/api/plants/' + plant.id;
  expect((await owner.request(path + '/graveyard', 'POST')).status).toBe(200);
  expect((await owner.request('/api/sites/' + plant.site_id, 'DELETE')).status).toBe(204);
  const before = await (await owner.request(path)).json();
  const history = await (await owner.request(path + '/history')).json();
  const photos = await (await owner.request(path + '/photos')).json();
  const sites = await (await owner.request('/api/sites')).json();
  const imageCalls = await imageHost.calls();
  imported.names.custom = 'Test must not replace retired name';
  imported.actions.watering.completed.date = '2026-06-09T12:00:00Z';
  imported.image.url = imageHost.url;
  fake.setPlants([imported]);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect((await (await owner.request(endpoint)).json())[0]).toMatchObject({ status: 'skipped', plant_id: plant.id });
  expect(await (await owner.request(path)).json()).toEqual(before);
  expect(await (await owner.request(path + '/history')).json()).toEqual(history);
  expect(await (await owner.request(path + '/photos')).json()).toEqual(photos);
  expect(await (await owner.request('/api/sites')).json()).toEqual(sites);
  expect(await (await owner.request(path + '/tasks')).json()).toEqual({});
  expect(await imageHost.calls()).toBe(imageCalls);
  expect((await owner.request(path + '/restore', 'POST', { site_id: sites[0].id })).status).toBe(200);
  expect((await owner.request(endpoint, 'POST', { code: fake.code })).status).toBe(200);
  expect((await owner.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(await (await owner.request(path)).json()).toMatchObject({ status: 'active', nickname: imported.names.custom });
});

test('allows only owners, scopes review and commit to the household, and checks origins', async () => {
  fake.setPlants([syntheticPlant('synthetic-access')]);
  const rows = await (await owner.request(endpoint, 'POST', { code: fake.code })).json();
  const operations = [[endpoint, 'GET', undefined], [endpoint, 'POST', { code: fake.code }], [endpoint + '/' + rows[0].id, 'PUT', { skip: true }], [endpoint + '/commit', 'POST', {}]] as const;
  for (const [path, method, body] of operations) {
    for (const cookie of ['', 'fytorium_session=invalid']) expect((await owner.request(path, method, body, cookie)).status).toBe(401);
    if (method !== 'GET') expect((await fetch(harness.base + path, { method, headers: { cookie: owner.cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: JSON.stringify(body) })).status).toBe(403);
  }
  const loggedOut = await owner.request('/import', 'GET', undefined, '');
  expect(loggedOut.status).toBe(303);
  expect(loggedOut.headers.get('location')).toBe('/auth/login');
  // This base has no invitation interface. SQL creates members only; import behavior stays at HTTP.
  const fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  try {
    for (const role of ['family', 'sitter']) await fixtureDb.query('INSERT INTO members (household_id, issuer, subject, role) SELECT household_id, issuer, $1, $2 FROM members LIMIT 1', ['synthetic-import-' + role, role]);
    await fixtureDb.query('DROP INDEX households_singleton');
    const foreign = await fixtureDb.query("INSERT INTO households (name) VALUES ('Test other import household') RETURNING id");
    await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-import-other', 'owner' FROM members LIMIT 1", [foreign.rows[0].id]);
  } finally { await fixtureDb.end(); }
  for (const role of ['family', 'sitter']) {
    const member = await harness.login('synthetic-import-' + role);
    for (const [path, method, body] of operations) expect((await member.request(path, method, body)).status).toBe(403);
    expect((await member.request('/import')).status).toBe(403);
  }
  const other = await harness.login('synthetic-import-other');
  expect(await (await other.request(endpoint)).json()).toEqual([]);
  expect((await other.request(endpoint + '/' + rows[0].id, 'PUT', { skip: true })).status).toBe(404);
  expect((await other.request(endpoint + '/commit', 'POST', {})).status).toBe(200);
  expect(await (await other.request('/api/plants')).json()).toEqual([]);
  expect((await (await owner.request(endpoint)).json())[0].status).toBe('confirmed');
  for (const id of ['invalid', randomUUID()]) expect((await owner.request(endpoint + '/' + id, 'PUT', { skip: true })).status).toBe(404);
  for (const value of [[], null, 'invalid']) expect((await owner.request(endpoint, 'POST', value)).status).toBe(400);
});

function importPlant(id: string) {
  const plant = syntheticPlant(id);
  plant.names.scientific = 'Testoria incert';
  return plant;
}

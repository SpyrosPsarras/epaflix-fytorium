import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness();
let base: string;
let cookie: string;
let fixtureDb: pg.Pool;
let request: Awaited<ReturnType<typeof harness.login>>['request'];

beforeAll(async () => {
  base = harness.base;
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  ({ cookie, request } = await harness.login());
});

afterAll(async () => {
  if (fixtureDb) await fixtureDb.end();
});

const conditions = {
  name: 'Test room', location: 'indoor', light: 'part_sun', grow_light: true,
  warm_min_c: 18, warm_max_c: 27, cold_min_c: null, cold_max_c: 21,
  humidity: 'normal', draft: false,
};

async function createSite(name = 'Test room') {
  const response = await request('/api/sites', 'POST', { ...conditions, name });
  expect(response.status).toBe(201);
  return response.json();
}

async function createPlant(siteId: string, nickname: string) {
  const search = await request('/api/species/search?q=Testoria%20lucida');
  expect(search.status).toBe(200);
  const result = await search.json();
  expect((await request('/api/plants', 'POST', { ...fixturePlant, nickname, site_id: siteId, search_id: result.search_id, gbif_key: 910001 })).status).toBe(201);
}

test('rejects cross-origin mutations without changing sites', async () => {
  const site = await createSite('Test origin room');
  const before = await (await request('/api/sites')).json();
  for (const [path, method] of [['/api/sites', 'POST'], ['/api/sites/' + site.id, 'PUT'], ['/api/sites/' + site.id, 'DELETE']]) {
    const response = await fetch(base + path, { method, headers: { cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: method === 'DELETE' ? undefined : JSON.stringify(conditions) });
    expect(response.status).toBe(403);
  }
  expect(await (await request('/api/sites')).json()).toEqual(before);
});

test('unknown site identifiers return 404 rather than a database error', async () => {
  for (const id of ['not-a-uuid', '00000000-0000-4000-8000-000000000000']) {
    for (const method of ['GET', 'PUT', 'DELETE']) {
      expect((await request('/api/sites/' + id, method, method === 'PUT' ? conditions : undefined)).status).toBe(404);
    }
  }
});

test('requires a household session for every site API operation', async () => {
  const site = await createSite('Test protected room');
  const before = await (await request('/api/sites')).json();
  for (const session of ['', 'fytorium_session=invalid']) {
    for (const [path, method, body] of [
      ['/api/sites', 'GET', undefined], ['/api/sites', 'POST', conditions],
      ['/api/sites/' + site.id, 'GET', undefined], ['/api/sites/' + site.id, 'PUT', conditions],
      ['/api/sites/' + site.id, 'DELETE', undefined],
    ] as const) {
      expect((await request(path, method, body, session)).status).toBe(401);
    }
  }
  for (const path of ['/sites', '/sites/new', '/sites/' + site.id]) {
    expect((await request(path, 'GET', undefined, '')).status).toBe(401);
  }
  expect(await (await request('/api/sites')).json()).toEqual(before);
});

test('refuses to delete a site with plants and explains how to fix it', async () => {
  const site = await createSite('Test occupied room');
  await createPlant(site.id, 'Test plant three');
  await createPlant(site.id, 'Test plant four');
  const response = await request('/api/sites/' + site.id, 'DELETE');
  expect(response.status).toBe(409);
  expect(await response.json()).toEqual({ message: 'Move or remove the 2 plants on this site first.' });
  expect((await request('/api/sites/' + site.id)).status).toBe(200);
  expect(await (await request('/api/sites')).json()).toEqual(expect.arrayContaining([
    expect.objectContaining({ id: site.id, plant_count: 2 }),
  ]));
});

test('deletes an empty site and removes it from the list', async () => {
  const site = await createSite('Test deletable room');
  expect((await request('/api/sites/' + site.id, 'DELETE')).status).toBe(204);
  expect((await request('/api/sites/' + site.id)).status).toBe(404);
  expect(await (await request('/api/sites')).json()).not.toEqual(expect.arrayContaining([expect.objectContaining({ id: site.id })]));
});

test('rejects invalid fields on create and edit without writing anything', async () => {
  const site = await createSite('Test validation room');
  const before = await (await request('/api/sites')).json();
  const cases: [string, unknown][] = [
    ['name', undefined], ['name', '   '], ['name', 42],
    ['location', 'elsewhere'], ['light', 'bright'], ['humidity', 'wet'],
    ['grow_light', 'true'], ['draft', 1],
    ['warm_min_c', 'not a number'], ['warm_max_c', 20.5],
    ['cold_min_c', '10'], ['cold_max_c', 2147483648],
    ['warm_min_c', 28], ['cold_min_c', 22],
  ];
  for (const [field, value] of cases) {
    const invalid = { ...conditions, name: 'Test validation room', [field]: value };
    for (const [path, method] of [['/api/sites', 'POST'], ['/api/sites/' + site.id, 'PUT']]) {
      const response = await request(path, method, invalid);
      expect(response.status, `${method} ${field}=${String(value)}`).toBe(422);
      expect((await response.json()).errors[field]).toEqual(expect.any(String));
    }
  }
  for (const body of [null, [], 'invalid']) {
    const response = await request('/api/sites', 'POST', body);
    expect(response.status).toBe(400);
    expect((await response.json()).errors._form).toEqual(expect.any(String));
  }
  const malformed = await fetch(base + '/api/sites', { method: 'POST', headers: { cookie, 'content-type': 'application/json' }, body: '{' });
  expect(malformed.status).toBe(400);
  expect((await malformed.json()).errors._form).toEqual(expect.any(String));
  expect(await (await request('/api/sites')).json()).toEqual(before);
});

test('lists site names, locations and counts from real plants', async () => {
  const site = await createSite('Test counted room');
  const empty = await createSite('Test empty terrace');
  await createPlant(site.id, 'Test plant one');
  await createPlant(site.id, 'Test plant two');
  const response = await request('/api/sites');
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual(expect.arrayContaining([
    expect.objectContaining({ id: site.id, name: 'Test counted room', location: 'indoor', plant_count: 2 }),
    expect.objectContaining({ id: empty.id, name: 'Test empty terrace', location: 'indoor', plant_count: 0 }),
  ]));
});

test('edits all site conditions, including clearing optional temperature bounds', async () => {
  const site = await createSite('Test edit room');
  const edited = { name: 'Test outdoor site', location: 'outdoor', light: 'full_shade', grow_light: false,
    warm_min_c: null, warm_max_c: null, cold_min_c: -5, cold_max_c: 10, humidity: 'humid', draft: true };
  const response = await request('/api/sites/' + site.id, 'PUT', edited);
  expect(response.status).toBe(200);
  expect(await (await request('/api/sites/' + site.id)).json()).toMatchObject(edited);
});

test('creates a household site and retrieves its stored conditions', async () => {
  const site = await createSite();
  expect(site.id).toMatch(/^[0-9a-f-]{36}$/);
  const response = await request('/api/sites/' + site.id);
  expect(response.status).toBe(200);
  expect(await response.json()).toMatchObject({ ...conditions, id: site.id });
});

test('a session loses site access when its member is removed from the household', async () => {
  const site = await createSite('Test removed member room');
  const identity = await (await request('/api/me')).json();
  // Invitations and membership removal have no API yet. SQL supplies the revoked-membership fixture.
  await fixtureDb.query('DELETE FROM members WHERE id = $1', [identity.member.id]);
  for (const [path, method, body] of [
    ['/api/sites', 'GET', undefined], ['/api/sites', 'POST', conditions],
    ['/api/sites/' + site.id, 'GET', undefined], ['/api/sites/' + site.id, 'PUT', conditions],
    ['/api/sites/' + site.id, 'DELETE', undefined],
    ['/sites', 'GET', undefined], ['/sites/new', 'GET', undefined], ['/sites/' + site.id, 'GET', undefined],
  ] as const) {
    expect((await request(path, method, body)).status).toBe(401);
  }
});

import { beforeAll, expect, test } from 'vitest';
import sharp from 'sharp';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

// No test clock: exercise SvelteKit's built server in production mode.
const harness = createApiHarness({ photos: true });
let cookie: string, access: string, plant: string, image: Uint8Array;
beforeAll(async () => {
  const owner = await harness.login();
  cookie = owner.cookie;
  access = (await harness.appDevice()).access_token;
  const site = await (await owner.request('/api/sites', 'POST', { name: 'App photo room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  plant = (await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json()).id;
  image = new Uint8Array(await sharp({ create: { width: 16, height: 16, channels: 3, background: '#408040' } }).jpeg().toBuffer());
});

function upload(path: string, headers: Record<string, string>, field: string) {
  const form = new FormData();
  form.set(field, new Blob([new Uint8Array(image)], { type: 'image/jpeg' }), 'synthetic.jpg');
  return fetch(harness.base + path, { method: 'POST', headers: { accept: 'application/json', ...headers }, body: form });
}

test('production bearer photo upload and identification pass without Origin', async () => {
  const headers = { authorization: 'Bearer ' + access };
  expect((await upload('/api/plants/' + plant + '/photos', headers, 'photo')).status).toBe(201);
  expect((await upload('/api/identification', headers, 'images')).status).toBe(200);
});

test.each([undefined, 'https://foreign.invalid', 'null'])('production cookie multipart keeps CSRF protection with Origin %s', async origin => {
  const headers: Record<string, string> = { cookie };
  if (origin !== undefined) headers.origin = origin;
  expect((await upload('/api/identification', headers, 'images')).status).toBe(403);
});

test('a foreign Origin cannot log a browser session out through the non-API route', async () => {
  const response = await fetch(harness.base + '/auth/logout', { method: 'POST', redirect: 'manual',
    headers: { cookie, origin: 'https://foreign.invalid', 'content-type': 'application/json' }, body: '{}' });
  expect(response.status).toBe(403);
  expect((await fetch(harness.base + '/api/me', { headers: { cookie } })).status).toBe(200);
});

test('same-origin cookie uploads still work', async () => {
  expect((await upload('/api/identification', { cookie, origin: harness.base }, 'images')).status).toBe(200);
});

test.each(['Bearer invalid', 'Basic invalid', 'Bearer ' + 'x'.repeat(43)])('an invalid Authorization header cannot use a cookie or bypass CSRF: %s', async authorization => {
  const response = await upload('/api/identification', { cookie, authorization }, 'images');
  expect(response.status).toBe(401);
  expect(response.headers.get('cache-control')).toBe('no-store');
  expect(response.headers.get('x-content-type-options')).toBe('nosniff');
  expect(response.headers.get('referrer-policy')).toBe('no-referrer');
  expect(await response.json()).toEqual({ error: 'invalid_token' });
});

test.each(['application/x-www-form-urlencoded', 'text/plain', 'multipart/form-data; boundary=synthetic'])('cookie form content type %s remains protected for every unsafe method', async contentType => {
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
    const response = await fetch(harness.base + '/api/sites', { method, headers: { cookie, 'content-type': contentType }, body: 'synthetic' });
    expect(response.status).toBe(403);
  }
});

test('foreign-origin cookie JSON writes stay blocked; valid bearer writes do not depend on Origin', async () => {
  const body = JSON.stringify({ name: 'Synthetic site', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false });
  const credentials: Record<string, string>[] = [{ cookie }, { authorization: 'Bearer ' + access }];
  for (const headers of credentials) {
    const response = await fetch(harness.base + '/api/sites', { method: 'POST', headers: { ...headers, origin: 'https://foreign.invalid', 'content-type': 'application/json' }, body });
    expect(response.status).toBe('cookie' in headers ? 403 : 201);
  }
});

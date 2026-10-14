import { beforeAll, expect, test } from 'vitest';
import sharp from 'sharp';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';

const harness = createApiHarness({ photos: true, testClock: true });
let owner: Awaited<ReturnType<typeof harness.login>>;
let plantId: string;
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T07:00:00Z' }) });
  owner = await harness.login();
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Synthetic A15 room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  plantId = (await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001, nickname: 'Synthetic A15 fern' })).json()).id;
});

test('Today and Upcoming carry the current cover ID, clear it after deletion, and keep sitter photos private', async () => {
  const row = async (view: string, member = owner) => {
    const list = await (await member.request('/api/tasks?view=' + view)).json();
    const water = list.groups.water.find((task: { plant_id: string }) => task.plant_id === plantId);
    expect(list.sections.flatMap((section: { tasks: unknown[] }) => section.tasks)).toContainEqual(water);
    return water;
  };
  for (const view of ['today', 'upcoming']) expect(await row(view)).toHaveProperty('cover_photo_id', null);
  const bytes = await sharp({ create: { width: 320, height: 320, channels: 3, background: '#408060' } }).jpeg().toBuffer();
  const form = new FormData();
  form.set('photo', new Blob([new Uint8Array(bytes)], { type: 'image/jpeg' }), 'synthetic-a15.jpg');
  const upload = await fetch(harness.base + `/api/plants/${plantId}/photos`, { method: 'POST', headers: { cookie: owner.cookie, origin: harness.base }, body: form });
  expect(upload.status).toBe(201);
  const photo = await upload.json();
  expect((await owner.request(`/api/photos/${photo.id}/cover`, 'PUT')).status).toBe(200);
  for (const view of ['today', 'upcoming']) expect(await row(view)).toHaveProperty('cover_photo_id', photo.id);
  expect((await owner.request(`/api/photos/${photo.id}/thumb`)).status).toBe(200);
  const invite = await (await owner.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [plantId] })).json();
  const sitter = await harness.login('synthetic-a15-sitter', {}, invite.link.split('/').at(-1));
  for (const view of ['today', 'upcoming']) {
    expect(await row(view, sitter)).toMatchObject({ cover_photo_id: null, site_name: '', basedOn: [] });
  }
  expect((await sitter.request(`/api/photos/${photo.id}/thumb`)).status).toBe(403);
  expect((await sitter.request(`/api/photos/${photo.id}/original`)).status).toBe(403);
  expect((await owner.request(`/api/photos/${photo.id}`, 'DELETE')).status).toBe(204);
  for (const view of ['today', 'upcoming']) expect(await row(view)).toHaveProperty('cover_photo_id', null);
});

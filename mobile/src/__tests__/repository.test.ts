import type { RawResponse } from '../../modules/fytorium-native';
import { NetworkError } from '../api/client';
import { CareCache, type Member } from '../data/cache';
import { createRepository } from '../data/repository';
import { PhotoCache, type Disk } from '../data/photos';
import { Drafts } from '../data/drafts';
import { nodeDb } from './helpers/nodeDb';

const identity = (id: string, role: string) => JSON.stringify({ member: { id, role, display_name: 'Synthetic' }, household: { id: 'h1' } });
const task = { status: 'due', dueOn: '2026-11-27', intervalDays: 7, basedOn: [{ input: 'species', source: 'synthetic', adjustment: 'none' }] };
const tasks = (view: string) => JSON.stringify({
  today: '2026-11-27', view, settings: { sort: 'task', filter: 'all', show_completed: false },
  groups: { watering: [task] }, sections: [{ key: 'watering', label: 'Water', tasks: [task] }],
});
const plantRow = (id: string, name: string) => ({ id, display_name: name, scientific_name: 'Testoria lucida', status: 'active', pot_size_cm: null });
const TODAY = tasks('today');
const UPCOMING = tasks('upcoming');
const PLANTS = JSON.stringify([plantRow('p1', 'Synthetic fern')]);
const DETAIL = JSON.stringify(plantRow('p1', 'Synthetic fern'));
const META = '{"timezone":"Europe/Athens","minAppVersion":null}';
type Reply = Partial<RawResponse> | 'drop' | Promise<Partial<RawResponse>>;

function server() {
  const routes = new Map<string, () => Reply>();
  const seen: string[] = [];
  const api = {
    request: async (path: string, options?: { query?: Record<string, string>; binary?: boolean }) => {
      const key = path + (options?.query?.view ? `?view=${options.query.view}` : '');
      seen.push(key);
      const reply = await (routes.get(key)?.() ?? { status: 404 });
      if (reply === 'drop') throw new NetworkError("Can't reach Fytorium.");
      return { status: 200, headers: {}, body: '', ...reply } as RawResponse;
    },
  };
  return { api, seen, route: (key: string, reply: () => Reply) => routes.set(key, reply) };
}

function setup(online = true) {
  const db = nodeDb();
  const cache = new CareCache(db);
  const s = server();
  let connected = online;
  const changes: (Member | null)[] = [];
  const repository = createRepository({ api: s.api as never, cache, online: () => connected, onMemberChanged: (member) => changes.push(member), now: () => 1_000 });
  s.route('/api/me', () => ({ body: identity('m1', 'owner') }));
  s.route('/api/tasks?view=today', () => ({ body: TODAY }));
  s.route('/api/tasks?view=upcoming', () => ({ body: UPCOMING }));
  s.route('/api/plants', () => ({ body: PLANTS }));
  s.route('/api/meta', () => ({ body: META }));
  return { db, cache, repository, ...s, changes, setOnline: (value: boolean) => { connected = value; } };
}

test('a refresh caches every list for the member, and a cold start reads them back offline', async () => {
  const h = setup();
  await h.repository.refresh();
  expect(h.repository.get()).toMatchObject({ member: { id: 'm1', role: 'owner' }, today: TODAY, plants: PLANTS, lastUpdated: 1000 });
  const cold = createRepository({ api: h.api as never, cache: new CareCache(h.db), online: () => false, onMemberChanged: () => undefined });
  cold.restore();
  expect(cold.get()).toMatchObject({ member: { id: 'm1' }, upcoming: UPCOMING, offline: true });
});

test('a different member, or the same member in another role, starts an empty cache', async () => {
  const h = setup();
  await h.repository.refresh();
  await h.repository.plant('p1');
  h.route('/api/me', () => ({ body: identity('m1', 'sitter') }));
  h.route('/api/plants', () => 'drop');
  await h.repository.refresh();
  expect(h.changes).toEqual([{ id: 'm1', role: 'owner' }, { id: 'm1', role: 'sitter' }]);
  expect(h.repository.get().plants).toBeNull();
  expect(h.cache.get({ id: 'm1', role: 'sitter' }, 'plant/p1')).toBeNull();
  expect(h.db.all('SELECT resource FROM cached_care')).toEqual([{ resource: 'identity' }]);
});

test('logout wipes the cache, and a read that returns after the wipe is ignored', async () => {
  const h = setup();
  await h.repository.refresh();
  let release!: (reply: Partial<RawResponse>) => void;
  h.route('/api/plants', () => new Promise((resolve) => { release = resolve; }));
  const late = h.repository.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  h.repository.wipe();
  release({ body: JSON.stringify([plantRow('stale', 'Stale')]) });
  await late;
  expect(h.repository.get()).toMatchObject({ member: null, plants: null, busy: false });
  expect(h.db.all('SELECT * FROM cached_care')).toEqual([]);
});

test('offline versus a server that is not responding', async () => {
  const h = setup();
  await h.repository.refresh();
  h.route('/api/me', () => ({ status: 503 }));
  await h.repository.refresh();
  expect(h.repository.get()).toMatchObject({ offline: false, serverUnavailable: true, plants: PLANTS });
  h.route('/api/me', () => 'drop');
  await h.repository.refresh();
  expect(h.repository.get()).toMatchObject({ offline: false, serverUnavailable: true });
  h.setOnline(false);
  h.repository.connectivityChanged(false);
  await h.repository.refresh();
  expect(h.repository.get()).toMatchObject({ offline: true, serverUnavailable: false, plants: PLANTS });
  h.setOnline(true);
  h.route('/api/me', () => ({ body: identity('m1', 'owner') }));
  await h.repository.refresh();
  expect(h.repository.get()).toMatchObject({ offline: false, serverUnavailable: false });
});

test('a plant page reads through the cache and shows the last copy offline', async () => {
  const h = setup();
  await h.repository.refresh();
  h.route('/api/plants/p1', () => ({ body: DETAIL }));
  expect((await h.repository.plant('p1'))?.json).toBe(DETAIL);
  h.setOnline(false);
  expect((await h.repository.plant('p1'))?.json).toBe(DETAIL);
  await expect(h.repository.plant('../x')).rejects.toThrow('Invalid plant');
});

function memoryDisk() {
  const files = new Map<string, string>();
  const disk: Disk = {
    uri: (folder, name) => (files.has(`${folder}/${name}`) ? `file:///cache/photos/${folder}/${name}` : null),
    write: async (folder, name, base64) => { files.set(`${folder}/${name}`, base64); },
    remove: (folder, name) => { files.delete(`${folder}/${name}`); },
    removeAll: () => files.clear(),
  };
  return { disk, files };
}

const base64Of = (bytes: number) => Buffer.alloc(bytes, 1).toString('base64');
const owner: Member = { id: 'm1', role: 'owner' };

test('photo copies stay under the cap, oldest use first out, and none over 1 MiB', async () => {
  const db = nodeDb();
  const { disk, files } = memoryDisk();
  const s = server();
  for (const id of ['a', 'b', 'c']) s.route(`/api/photos/${id}/thumb`, () => ({ body: base64Of(400_000) }));
  s.route('/api/photos/big/display', () => ({ body: base64Of(1_048_577) }));
  const photos = new PhotoCache(db, disk, s.api as never, () => true, () => owner, 1_000_000);
  expect(await photos.load(owner, 'a')).toBe('file:///cache/photos/m1.owner/a.thumb.jpg');
  await photos.load(owner, 'b');
  await photos.load(owner, 'a');
  await photos.load(owner, 'c');
  expect([...files.keys()].sort()).toEqual(['m1.owner/a.thumb.jpg', 'm1.owner/c.thumb.jpg']);
  expect(photos.totalBytes()).toBe(800_000);
  expect(await photos.load(owner, 'big', 'display')).toBeNull();
  expect(files.size).toBe(2);
  await expect(photos.load(owner, '../a')).rejects.toThrow('Invalid photo');
});

test('a cached photo shows offline, a missing one does not fetch, and a wipe drops everything including a late download', async () => {
  const db = nodeDb();
  const { disk, files } = memoryDisk();
  const s = server();
  let online = true;
  let release!: () => void;
  s.route('/api/photos/a/display', () => ({ body: base64Of(10) }));
  s.route('/api/photos/late/display', () => new Promise((resolve) => { release = () => resolve({ body: base64Of(10) }); }));
  let bound: Member | null = owner;
  const photos = new PhotoCache(db, disk, s.api as never, () => online, () => bound);
  await photos.load(owner, 'a', 'display');
  online = false;
  expect(await photos.load(owner, 'a', 'display')).not.toBeNull();
  expect(await photos.load(owner, 'b', 'display')).toBeNull();
  expect(s.seen).toEqual(['/api/photos/a/display']);
  online = true;
  const late = photos.load(owner, 'late', 'display');
  await new Promise((resolve) => setImmediate(resolve));
  photos.wipe();
  release();
  expect(await late).toBeNull();
  expect(files.size).toBe(0);
  expect(db.all('SELECT * FROM photo_cache')).toEqual([]);
  bound = { id: 'm2', role: 'owner' };
  expect(await photos.load(owner, 'a', 'display')).toBeNull();
  expect(files.size).toBe(0);
});

test('drafts go with their page route and at a wipe', () => {
  const drafts = new Drafts(nodeDb());
  drafts.put('m1.owner/Plants/plant:p1/edit', { nickname: 'Fern' });
  drafts.put('m1.owner/Plants/plant:p1', { scroll: 3 });
  drafts.put('m1.owner/Plants/plant:p10', { scroll: 4 });
  drafts.removeUnder('m1.owner/Plants/plant:p1');
  expect(drafts.get('m1.owner/Plants/plant:p1/edit')).toBeNull();
  expect(drafts.get('m1.owner/Plants/plant:p10')).toEqual({ scroll: 4 });
  drafts.wipe();
  expect(drafts.get('m1.owner/Plants/plant:p10')).toBeNull();
});

test('a plant read started before a role change is dropped, even if the role changes back before it returns', async () => {
  const h = setup();
  await h.repository.refresh();
  let release!: (reply: Partial<RawResponse>) => void;
  h.route('/api/plants/p1', () => new Promise((resolve) => { release = resolve; }));
  const late = h.repository.plant('p1');
  await new Promise((resolve) => setImmediate(resolve));
  h.route('/api/me', () => ({ body: identity('m1', 'sitter') }));
  await h.repository.refresh();
  h.route('/api/me', () => ({ body: identity('m1', 'owner') }));
  await h.repository.refresh();
  release({ body: JSON.stringify(plantRow('p1', 'Owner copy')) });
  expect(await late).toBeNull();
  expect(h.cache.get({ id: 'm1', role: 'owner' }, 'plant/p1')).toBeNull();
});

test("a failure from before a role change does not mark the new role's session", async () => {
  const h = setup();
  await h.repository.refresh();
  let release!: (reply: Partial<RawResponse>) => void;
  h.route('/api/plants/p1', () => new Promise((resolve) => { release = resolve; }));
  const late = h.repository.plant('p1');
  await new Promise((resolve) => setImmediate(resolve));
  h.route('/api/me', () => ({ body: identity('m1', 'sitter') }));
  await h.repository.refresh();
  release({ status: 503 });
  await late;
  expect(h.repository.get()).toMatchObject({ member: { role: 'sitter' }, serverUnavailable: false });
});

test('a 200 the lists cannot use keeps the last good copy and its time, and says Fytorium is not responding', async () => {
  const h = setup();
  await h.repository.refresh();
  let now = 2_000;
  const repository = createRepository({ api: h.api as never, cache: h.cache, online: () => true, onMemberChanged: () => undefined, now: () => now });
  const badTasks = (change: object) => JSON.stringify({ ...JSON.parse(TODAY), ...change });
  for (const [route, body] of [
    ['/api/plants', '<html>proxy</html>'], ['/api/plants', '{"rows":[]}'], ['/api/tasks?view=today', '[]'], ['/api/meta', '{}'], ['/api/me', '{}'],
    ['/api/tasks?view=today', badTasks({ sections: [null] })], ['/api/tasks?view=today', badTasks({ groups: { watering: null } })],
    ['/api/tasks?view=today', badTasks({ groups: { watering: [{ status: 'due' }] } })], ['/api/tasks?view=today', badTasks({ settings: {} })],
    ['/api/tasks?view=today', badTasks({ groups: { watering: [{ ...task, completed_event: { id: 'e1' } }] } })],
    ['/api/plants', JSON.stringify([{ id: 'p1', display_name: 'Synthetic fern' }])], ['/api/meta', '{"timezone":"Not/AZone"}'],
  ]) {
    h.route('/api/me', () => ({ body: identity('m1', 'owner') }));
    h.route('/api/plants', () => ({ body: PLANTS }));
    h.route('/api/tasks?view=today', () => ({ body: TODAY }));
    h.route('/api/meta', () => ({ body: META }));
    h.route(route, () => ({ body }));
    now += 1;
    await repository.refresh();
    expect(repository.get()).toMatchObject({ plants: PLANTS, today: TODAY, lastUpdated: 1000, serverUnavailable: true });
  }
});

test('a plant page 200 the page cannot use keeps the last good copy', async () => {
  const h = setup();
  await h.repository.refresh();
  h.route('/api/plants/p1', () => ({ body: DETAIL }));
  await h.repository.plant('p1');
  const kept = h.cache.get({ id: 'm1', role: 'owner' }, 'plant/p1');
  for (const body of ['<html>proxy</html>', '[]', '{"id":"p1"}', JSON.stringify(plantRow('p2', 'Another plant'))]) {
    h.route('/api/plants/p1', () => ({ body }));
    expect(await h.repository.plant('p1')).toEqual(kept);
    expect(h.repository.get().serverUnavailable).toBe(true);
  }
});

test('a refresh asked for during the one in flight does not run after logout', async () => {
  const h = setup();
  let release!: () => void;
  h.route('/api/me', () => new Promise((resolve) => { release = () => resolve({ body: identity('m1', 'owner') }); }));
  const first = h.repository.refresh();
  await new Promise((resolve) => setTimeout(resolve, 0));
  void h.repository.refresh();
  h.repository.wipe();
  release();
  await first;
  expect(h.seen.filter((key) => key === '/api/me')).toHaveLength(1);
});

test('a refresh that lands after the connection went keeps its data and still shows Offline', async () => {
  const h = setup();
  let release!: (reply: Partial<RawResponse>) => void;
  h.route('/api/meta', () => new Promise((resolve) => { release = resolve; }));
  const refresh = h.repository.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  h.setOnline(false);
  h.repository.connectivityChanged(false);
  release({ body: META });
  await refresh;
  expect(h.repository.get()).toMatchObject({ plants: PLANTS, offline: true, serverUnavailable: false });
});

test('a plant read that answers after a newer one does not replace it', async () => {
  const h = setup();
  await h.repository.refresh();
  const releases: ((reply: Partial<RawResponse>) => void)[] = [];
  h.route('/api/plants/p1', () => new Promise((resolve) => { releases.push(resolve); }));
  const older = h.repository.plant('p1');
  const newer = h.repository.plant('p1');
  await new Promise((resolve) => setImmediate(resolve));
  releases[1]({ body: JSON.stringify(plantRow('p1', 'New name')) });
  await newer;
  releases[0]({ status: 503 });
  await older;
  releases.length = 0;
  const late = h.repository.plant('p1');
  const later = h.repository.plant('p1');
  await new Promise((resolve) => setImmediate(resolve));
  releases[1]({ body: JSON.stringify(plantRow('p1', 'Newest name')) });
  await later;
  releases[0]({ body: JSON.stringify(plantRow('p1', 'Old name')) });
  await late;
  expect(JSON.parse(h.cache.get({ id: 'm1', role: 'owner' }, 'plant/p1')!.json).display_name).toBe('Newest name');
  expect(h.repository.get().serverUnavailable).toBe(false);
});

test('the plant list alone refreshes while the task lists fail', async () => {
  const h = setup();
  await h.repository.refresh();
  h.route('/api/tasks?view=today', () => ({ status: 503 }));
  const fresh = JSON.stringify([plantRow('p1', 'Synthetic fern'), plantRow('p2', 'Synthetic ivy')]);
  h.route('/api/plants', () => ({ body: fresh }));
  await h.repository.refreshPlants();
  expect(h.repository.get()).toMatchObject({ plants: fresh, today: TODAY });
});

test('the newest plant list request wins, whichever path read it and whichever answers last', async () => {
  const h = setup();
  await h.repository.refresh();
  const lists: ((reply: Partial<RawResponse>) => void)[] = [];
  h.route('/api/plants', () => new Promise((resolve) => { lists.push(resolve); }));
  const list = (name: string) => ({ body: JSON.stringify([plantRow('p1', name)]) });
  const names = () => JSON.parse(h.repository.get().plants!)[0].display_name;

  const full = h.repository.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  const alone = h.repository.refreshPlants();
  await new Promise((resolve) => setImmediate(resolve));
  lists[1](list('Newer'));
  await alone;
  lists[0](list('Older'));
  await full;
  expect(names()).toBe('Newer');
  expect(h.repository.get().today).toBe(TODAY);

  lists.length = 0;
  const first = h.repository.refreshPlants();
  await new Promise((resolve) => setImmediate(resolve));
  const second = h.repository.refresh();
  await new Promise((resolve) => setImmediate(resolve));
  lists[1](list('Newest'));
  await second;
  lists[0](list('Stale'));
  await first;
  expect(names()).toBe('Newest');
});

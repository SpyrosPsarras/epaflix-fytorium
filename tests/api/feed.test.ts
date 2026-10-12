import { afterAll, beforeAll, expect, test } from 'vitest';
import pg from 'pg';
import { randomUUID } from 'node:crypto';
import { fixturePlant } from '../plant-fixture';
import { createApiHarness } from './harness';
import type { FeedCard, FeedSettings } from '../../src/lib/feed';
import type { TaskList } from '../../src/lib/tasks';

const dates = ['2030-06-01', '2030-06-02', '2030-06-03', '2030-06-04', '2030-06-05', '2030-06-06', '2030-06-07'];
let precipitationMm = 0;
let weatherStatus = 200;
const harness = createApiHarness({ testClock: true, queryStatistics: true, weather: () => ({ status: weatherStatus, body: { daily: {
  time: dates, temperature_2m_min: dates.map(() => 0), temperature_2m_max: dates.map(() => 15),
  precipitation_sum: dates.map(() => precipitationMm), daylight_duration: dates.map(() => 43200),
} } }) });
let member: Awaited<ReturnType<typeof harness.login>>;
let plantId: string;
let siteId: string;
let fixtureDb: pg.Pool;
const feed = async () => {
  const response = await member.request('/api/feed');
  expect(response.status).toBe(200);
  return await response.json() as { cards: FeedCard[] };
};
beforeAll(async () => {
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-05-31T12:00:00Z' }) });
  member = await harness.login();
  const site = await (await member.request('/api/sites', 'POST', { name: 'Test feed site', location: 'outdoor', light: 'part_shade', humidity: 'normal', draft: false, grow_light: false })).json();
  siteId = site.id;
  const search = await (await member.request('/api/species/search?q=Testoria%20lucida')).json();
  const added = await member.request('/api/plants', 'POST', { ...fixturePlant, pot_size_cm: 20, nickname: 'Test feed plant', site_id: siteId, search_id: search.search_id, gbif_key: 910001 });
  expect(added.status).toBe(201);
  plantId = (await added.json()).id;
  await member.request('/api/species/910001/care/overrides/light', 'PUT', { value: 'part_sun' });
  await member.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T12:00:00Z' });
  await member.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
  expect((await member.request('/__test/weather-worker', 'POST')).status).toBe(200);
  fixtureDb = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
  await fixtureDb.query('CREATE EXTENSION pg_stat_statements');
});
afterAll(async () => { await fixtureDb?.end(); });

test('feed query count stays fixed as the collection grows, regardless of Today filters', async () => {
  const calls = async () => Number((await fixtureDb.query('SELECT sum(calls) AS n FROM pg_stat_statements')).rows[0].n);
  const measure = async () => {
    const counts: number[] = [];
    // The name worker polls in the background. The minimum of three fast reads excludes its tick.
    for (let i = 0; i < 3; i++) {
      const before = await calls();
      await feed();
      counts.push(await calls() - before - 1); // Exclude the preceding statistics read itself.
    }
    return Math.min(...counts);
  };
  expect(await measure()).toBe(16);
  const ids: string[] = [];
  try {
    for (let i = 0; i < 25; i++) {
      const search = await (await member.request('/api/species/search?q=Testoria%20lucida')).json();
      const response = await member.request('/api/plants', 'POST', { ...fixturePlant, site_id: siteId, nickname: 'Test scaled feed ' + i, search_id: search.search_id, gbif_key: 910001 });
      expect(response.status).toBe(201);
      ids.push((await response.json()).id);
    }
    await member.request('/api/tasks/settings', 'PUT', { sort: 'plant', filter: 'indoor', show_completed: true });
    expect((await feed()).cards.filter(card => card.kind === 'plant_warnings')).toHaveLength(26);
    const home = await (await member.request('/')).text();
    expect(home).toContain('Test feed plant');
    expect(home.split('id="today"')[1].split('</section>')[0]).not.toContain('Test feed plant');
    expect(await measure()).toBe(16);
  } finally {
    for (let i = 0; i < ids.length; i++) await member.request('/api/plants/' + ids[i], 'DELETE', { confirm_name: 'Test scaled feed ' + i });
    await member.request('/api/tasks/settings', 'PUT', { sort: 'task', filter: 'all', show_completed: false });
  }
});

const allCards: FeedSettings = { late_tasks: true, current_weather: true, weather_alerts: true, plant_warnings: true };

test('multiple late plants keep individual destinations and a Today see-all link', async () => {
  const search = await (await member.request('/api/species/search?q=Testoria%20lucida')).json();
  const added = await member.request('/api/plants', 'POST', { ...fixturePlant, site_id: siteId, nickname: 'Test second late plant', search_id: search.search_id, gbif_key: 910001 });
  expect(added.status).toBe(201);
  const secondId = (await added.json()).id;
  try {
    await member.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-02T12:00:00Z' });
    expect((await feed()).cards).toContainEqual(expect.objectContaining({ kind: 'late_tasks', href: '/#today', plants: [{ id: plantId, name: 'Test feed plant' }, { id: secondId, name: 'Test second late plant' }] }));
    const home = await (await member.request('/')).text();
    expect(home).toContain('href="/plants/' + secondId + '"');
  } finally {
    await member.request('/api/plants/' + secondId, 'DELETE', { confirm_name: 'Test second late plant' });
    await member.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T12:00:00Z' });
  }
});

test('late custom tasks count in the feed exactly as in Today', async () => {
  const ids: string[] = [];
  try {
    for (const target of [{ plant_id: plantId }, { site_id: siteId }]) {
      const response = await member.request('/api/custom-tasks', 'POST', { ...target, name: 'Test feed custom task', interval_days: 1 });
      expect(response.status).toBe(201);
      ids.push((await response.json()).id);
    }
    await member.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-03T12:00:00Z' });
    const today: TaskList = await (await member.request('/api/tasks?view=today')).json();
    const late = Object.values(today.groups).flat().filter(task => task.status === 'due' && task.lateDays > 0);
    expect(late.filter(task => ids.includes(task.custom_task_id ?? ''))).toHaveLength(2);
    expect((await feed()).cards.find(card => card.kind === 'late_tasks')).toMatchObject({ count: late.length });
  } finally {
    for (const id of ids) await member.request('/api/custom-tasks/' + id, 'DELETE');
    await member.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T12:00:00Z' });
  }
});

test('sitter feed contains only shared late care, stays fixed-query, and disappears on removal or graveyard', async () => {
  const search = await (await member.request('/api/species/search?q=Testoria%20lucida')).json();
  const other = await (await member.request('/api/plants', 'POST', { ...fixturePlant, site_id: siteId, nickname: 'Private feed plant', search_id: search.search_id, gbif_key: 910001 })).json();
  const invite = await (await member.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [plantId] })).json();
  const sitter = await harness.login('synthetic-feed-sitter', {}, invite.link.split('/').at(-1));
  const sitterId = (await (await sitter.request('/api/me')).json()).member.id;
  const calls = async () => Number((await fixtureDb.query('SELECT sum(calls) AS n FROM pg_stat_statements')).rows[0].n);
  try {
    const counts: number[] = [];
    for (let i = 0; i < 3; i++) {
      const before = await calls();
      const response = await sitter.request('/api/feed');
      counts.push(await calls() - before - 1);
      expect(response.status).toBe(200);
      const result = await response.json();
      expect(Object.keys(result)).toEqual(['cards']);
      expect(result.cards).toEqual([{ id: 'late_tasks', kind: 'late_tasks', href: '/plants/' + plantId, count: 1, plants: [{ id: plantId, name: 'Test feed plant' }] }]);
    }
    expect(Math.min(...counts)).toBe(10);
    const feedOnlyReads = async () => Number((await fixtureDb.query(`SELECT COALESCE(sum(calls), 0) AS n FROM pg_stat_statements
      WHERE query ILIKE 'SELECT%' AND (query ILIKE '%feed_late_tasks%' OR query ILIKE '%FROM weather_events%' OR query ILIKE '%AS plant_count FROM care_actions%' OR query ILIKE '%count(*)%location%outdoor%')`)).rows[0].n);
    const beforeHome = await feedOnlyReads();
    expect((await sitter.request('/')).status).toBe(200);
    expect(await feedOnlyReads()).toBe(beforeHome);
    expect((await sitter.request('/api/feed/settings')).status).toBe(403);
    expect(await (await sitter.request('/settings/notifications')).text()).not.toContain('My home feed');
    expect((await sitter.request('/api/feed/settings', 'PUT', allCards)).status).toBe(403);
    expect((await sitter.request('/api/weather/rain/water', 'POST', { request_id: randomUUID() })).status).toBe(403);
    const home = await (await sitter.request('/')).text();
    expect(home).toContain('Test feed plant');
    for (const hidden of ['Private feed plant', 'Current weather', 'Weather alerts', 'Warnings for', 'Customize my feed']) expect(home).not.toContain(hidden);
    expect((await member.request('/api/plants/' + plantId + '/graveyard', 'POST')).status).toBe(200);
    expect(await (await sitter.request('/api/feed')).json()).toEqual({ cards: [] });
    expect((await member.request('/api/plants/' + plantId + '/restore', 'POST', {})).status).toBe(200);
    await member.request('/api/household/members/' + sitterId, 'PUT', { plant_ids: [] });
    expect(await (await sitter.request('/api/feed')).json()).toEqual({ cards: [] });
    await member.request('/api/household/members/' + sitterId, 'DELETE');
    expect((await sitter.request('/api/feed')).status).toBe(401);
  } finally {
    await member.request('/api/plants/' + plantId + '/restore', 'POST', {});
    await member.request('/api/plants/' + other.id, 'DELETE', { confirm_name: 'Private feed plant' });
  }
});

test('a returning sitter ignores feed switches saved as family', async () => {
  const invite = await (await member.request('/api/household/invites', 'POST', { role: 'family' })).json();
  const family = await harness.login('synthetic-feed-returning', {}, invite.link.split('/').at(-1));
  const id = (await (await family.request('/api/me')).json()).member.id;
  expect((await family.request('/api/feed/settings', 'PUT', { late_tasks: false, current_weather: false, weather_alerts: false, plant_warnings: false })).status).toBe(200);
  expect(await (await family.request('/api/feed')).json()).toMatchObject({ cards: [] });
  await member.request('/api/household/members/' + id, 'DELETE');
  const holiday = await (await member.request('/api/household/invites', 'POST', { role: 'sitter', plant_ids: [plantId] })).json();
  const sitter = await harness.login('synthetic-feed-returning', {}, holiday.link.split('/').at(-1));
  try {
    expect(await (await sitter.request('/api/feed')).json()).toEqual({ cards: [{ id: 'late_tasks', kind: 'late_tasks', href: '/plants/' + plantId, count: 1, plants: [{ id: plantId, name: 'Test feed plant' }] }] });
    expect(await (await sitter.request('/')).text()).toContain('aria-label="Late tasks"');
  } finally { await member.request('/api/household/members/' + id, 'DELETE'); }
});

test('validated feed preferences survive a fresh session without changing another member', async () => {
  expect(await (await member.request('/api/feed/settings')).json()).toEqual(allCards);
  // This base has no invitation API. Only membership setup uses SQL fixtures.
  const identity = await (await member.request('/api/me')).json();
  await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT household_id, issuer, 'synthetic-feed-family', 'family' FROM members WHERE id = $1", [identity.member.id]);
  const family = await harness.login('synthetic-feed-family');
  const hidden = { ...allCards, plant_warnings: false };
  expect((await member.request('/api/feed/settings', 'PUT', hidden)).status).toBe(200);
  expect(await (await member.request('/api/feed/settings')).json()).toEqual(hidden);
  expect((await feed()).cards.some(card => card.kind === 'plant_warnings')).toBe(false);
  const freshSession = await harness.login();
  expect(await (await freshSession.request('/api/feed/settings')).json()).toEqual(hidden);
  expect((await (await freshSession.request('/api/feed')).json()).cards.some((card: FeedCard) => card.kind === 'plant_warnings')).toBe(false);
  expect(await (await family.request('/api/feed/settings')).json()).toEqual(allCards);
  expect(await (await family.request('/settings/notifications')).text()).toContain('My home feed');
  expect((await family.request('/api/feed/settings', 'PUT', allCards)).status).toBe(200);
  expect((await (await family.request('/api/feed')).json()).cards.some((card: FeedCard) => card.kind === 'plant_warnings')).toBe(true);
  for (const kind of ['late_tasks', 'current_weather', 'weather_alerts', 'plant_warnings']) {
    expect((await member.request('/api/feed/settings', 'PUT', { ...allCards, [kind]: false })).status).toBe(200);
    expect((await feed()).cards.some(card => card.kind === kind)).toBe(false);
  }
  for (const input of [{ ...allCards, late_tasks: 'false' }, { ...allCards, other: true }, {}, { ...allCards, current_weather: null }]) {
    expect((await member.request('/api/feed/settings', 'PUT', input)).status).toBe(422);
  }
  for (const input of [null, [], 'invalid']) expect((await member.request('/api/feed/settings', 'PUT', input)).status).toBe(400);
  await member.request('/api/feed/settings', 'PUT', allCards);
});

test('feed shows late tasks, current weather, each open alert and explained plant warnings with working destinations', async () => {
  const result = await feed();
  expect(result.cards.map(card => card.kind)).toEqual(['late_tasks', 'current_weather', 'weather_alerts', 'plant_warnings']);
  expect(result.cards[0]).toMatchObject({ kind: 'late_tasks', count: 1, href: '/plants/' + plantId, plants: [{ id: plantId, name: 'Test feed plant' }] });
  expect(result.cards[1]).toMatchObject({ kind: 'current_weather', href: '/settings#weather-location-title', current: { date: '2030-06-01', minC: 0, maxC: 15 } });
  expect(result.cards[2]).toMatchObject({ kind: 'weather_alerts', href: '/plants/' + plantId, event: { kind: 'low_temperature', plant_id: plantId, starts_on: '2030-06-01', payload: { hardiness: { value: 5, source: 'rule', ruleId: 'hardiness.default@1' }, potRuleId: 'hardiness.pot_offset@1' } } });
  expect(result.cards[3]).toMatchObject({ kind: 'plant_warnings', href: '/plants/' + plantId, warnings: [{ kind: 'too_dark', reason: 'Effective light is part_shade; species needs part_sun. A gap of at least one light step is too dark.', inputs: [
    { input: 'careProfile.light', value: 'part_sun', source: 'override', fetchedAt: '2030-05-31T12:00:00.000Z' },
    { input: 'site.light', value: 'part_shade', source: 'setting', ruleId: 'warning.effective_light@1' },
    { input: 'site.grow_light', value: false, source: 'setting', ruleId: 'warning.effective_light@1' },
    { input: 'plant.grow_light', value: false, source: 'setting', ruleId: 'warning.effective_light@1' },
    { input: 'plant.window_distance_cm', value: 'not set', source: 'setting', ruleId: 'warning.effective_light@1' },
    { input: 'effective_light', value: 'part_shade', source: 'rule', ruleId: 'warning.effective_light@1' },
  ] }] });
  for (const card of result.cards) expect((await member.request(card.href.split('#')[0] || '/')).status).toBe(200);
});

test('configured weather keeps its waiting and unavailable notices without current data', async () => {
  await member.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .7 } });
  expect((await feed()).cards).toContainEqual(expect.objectContaining({ kind: 'current_weather', current: null, unavailableSince: null }));
  expect(await (await member.request('/')).text()).toContain('Weather is waiting for its background refresh.');
  weatherStatus = 503;
  try {
    await member.request('/__test/weather-worker', 'POST');
    expect((await feed()).cards).toContainEqual(expect.objectContaining({ kind: 'current_weather', current: null, unavailableSince: '2030-06-01T12:00:00.000Z' }));
    expect(await (await member.request('/')).text()).toContain('Weather unavailable since 2030-06-01T12:00:00.000Z');
  } finally {
    weatherStatus = 200;
    await member.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
    await member.request('/__test/weather-worker', 'POST');
  }
});

test('each open weather event has one card, including household rain, without duplicating cold alerts', async () => {
  precipitationMm = 6;
  await member.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .6 } });
  await member.request('/__test/weather-worker', 'POST');
  const alerts = (await feed()).cards.filter(card => card.kind === 'weather_alerts');
  expect(alerts).toHaveLength(2);
  expect(new Set(alerts.map(card => card.id)).size).toBe(2);
  expect(alerts).toContainEqual(expect.objectContaining({ href: '/settings#weather-location-title', event: expect.objectContaining({ kind: 'rain', plant_id: null }) }));
  await member.request('/api/plants/' + plantId + '/graveyard', 'POST');
  const withoutOutdoorPlants = (await feed()).cards.filter(card => card.kind === 'weather_alerts');
  expect(withoutOutdoorPlants).toEqual([]);
  await member.request('/api/plants/' + plantId + '/restore', 'POST', {});
  precipitationMm = 0;
  await member.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
  await member.request('/__test/weather-worker', 'POST');
});

test('rain watering remains undoable from its card after the rain report expires', async () => {
  precipitationMm = 6;
  await member.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .8 } });
  await member.request('/__test/weather-worker', 'POST');
  const watered = await member.request('/api/weather/rain/water', 'POST', { request_id: randomUUID() });
  expect(watered.status).toBe(200);
  const { batchId } = await watered.json();
  try {
    await member.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-10T12:00:00Z' });
    member = await harness.login();
    await member.request('/__test/weather-worker', 'POST');
    expect((await feed()).cards).toContainEqual(expect.objectContaining({ id: 'rain_batch', kind: 'weather_alerts', href: '/settings#weather-location-title' }));
    expect(await (await member.request('/')).text()).toContain('Undo rain watering');
    expect((await member.request('/api/task-batches/' + batchId + '/undo', 'POST')).status).toBe(200);
    expect((await feed()).cards.some(card => card.id === 'rain_batch')).toBe(false);
  } finally {
    precipitationMm = 0;
    await member.request('/__test/clock', 'POST', { action: 'set', at: '2030-06-01T12:00:00Z' });
    await member.request('/api/household/location', 'PUT', { location: { latitude: .5, longitude: .5 } });
    await member.request('/__test/weather-worker', 'POST');
  }
});

test('feed and preferences require a session, refuse foreign origins and never expose another household', async () => {
  for (const [path, method, body] of [
    ['/api/feed', 'GET', undefined], ['/api/feed/settings', 'GET', undefined], ['/api/feed/settings', 'PUT', allCards],
  ] as const) {
    for (const session of ['', 'fytorium_session=invalid']) expect((await member.request(path, method, body, session)).status).toBe(401);
    expect((await fetch(harness.base + path, { method, headers: { cookie: member.cookie, origin: 'https://untrusted.example', 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })).status).toBe(403);
  }
  await fixtureDb.query('DROP INDEX households_singleton');
  const foreign = (await fixtureDb.query("INSERT INTO households (name) VALUES ('Test foreign feed household') RETURNING id")).rows[0].id;
  await fixtureDb.query("INSERT INTO members (household_id, issuer, subject, role) SELECT $1, issuer, 'synthetic-feed-foreign', 'owner' FROM members LIMIT 1", [foreign]);
  const other = await harness.login('synthetic-feed-foreign');
  expect((await (await other.request('/api/feed?household_id=' + foreign)).json()).cards).toEqual([]);
  const own = await feed();
  expect((await (await other.request('/api/feed?household_id=' + (await (await member.request('/api/me')).json()).household.id)).json()).cards).toEqual([]);
  const settings = { ...allCards, plant_warnings: false };
  expect((await other.request('/api/feed/settings', 'PUT', { ...settings, member_id: (await (await member.request('/api/me')).json()).member.id })).status).toBe(422);
  await other.request('/api/feed/settings', 'PUT', settings);
  expect(await feed()).toEqual(own);
  const site = await (await other.request('/api/sites', 'POST', { name: 'Test foreign feed site', location: 'indoor', light: 'full_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await other.request('/api/species/search?q=Testoria%20lucida')).json();
  const plant = await (await other.request('/api/plants', 'POST', { ...fixturePlant, nickname: 'Test foreign feed plant', site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json();
  await other.request('/api/feed/settings', 'PUT', allCards);
  expect((await (await other.request('/api/feed')).json()).cards).toEqual([expect.objectContaining({ kind: 'plant_warnings', plantId: plant.id, warnings: [expect.objectContaining({ kind: 'missing_info' })] })]);
  expect((await feed()).cards.some(card => card.id === plant.id)).toBe(false);
  expect((await member.request('/plants/' + plant.id)).status).toBe(404);
});

test('cards with nothing to show disappear and graveyard plants never warn', async () => {
  await member.request('/api/plants/' + plantId + '/tasks/water/complete', 'POST', { request_id: randomUUID() });
  await member.request('/api/species/910001/care/overrides/light', 'PUT', { value: 'part_shade' });
  expect((await feed()).cards.map(card => card.kind)).toEqual(['current_weather', 'weather_alerts']);
  await member.request('/api/household/location', 'PUT', { location: null });
  expect((await feed()).cards.map(card => card.kind)).toEqual(['plant_warnings']);
  await member.request('/api/plants/' + plantId + '/graveyard', 'POST');
  expect((await feed()).cards).toEqual([]);
});

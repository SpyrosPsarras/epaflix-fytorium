import { afterEach, expect, test, vi } from 'vitest';
import { authorizeRoute, sitterView } from '../../src/lib/server/policy';
import type { Identity } from '../../src/lib/server/auth';

// No database/configuration is needed for the deny-by-default branch.
vi.mock('../../src/lib/server/config', () => ({ config: () => ({}) }));
afterEach(() => vi.restoreAllMocks());

test.each(['owner', 'family', 'sitter', null] as const)('unlisted pages and non-API handlers refuse %s with 404 and name the missing route', async role => {
  const identity: Identity | null = role ? { language: 'en', member: { id: 'synthetic-member', role }, household: { id: 'synthetic-household', name: 'Test household' } } : null;
  const warning = vi.spyOn(console, 'warn').mockImplementation(() => {});
  for (const route of ['/export', '/api/export']) {
    for (const method of ['GET', 'HEAD', 'POST']) {
      await expect(authorizeRoute(identity, route, method, {})).rejects.toMatchObject({ status: 404 });
      expect(warning).toHaveBeenLastCalledWith(expect.stringContaining(route));
    }
  }
});

test('unmatched requests leave the 404 to routing without policy warnings', async () => {
  const warning = vi.spyOn(console, 'warn').mockImplementation(() => {});
  await expect(authorizeRoute(null, null, 'GET', {})).resolves.toBeUndefined();
  expect(warning).not.toHaveBeenCalled();
});

test('home and feed projections allow only late-care fields, including nested plant keys', () => {
  const feed = { cards: [{ id: 'late_tasks', kind: 'late_tasks', href: '/plants/shared', count: 1,
    plants: [{ id: 'shared', name: 'Shared fern', private: 'hidden' }], private: 'hidden' },
    { id: 'weather', kind: 'weather_alerts' }, { id: 'warnings', kind: 'plant_warnings' }], weather: { private: 'hidden' }, settings: {} };
  const expected = { cards: [{ id: 'late_tasks', kind: 'late_tasks', href: '/plants/shared', count: 1, plants: [{ id: 'shared', name: 'Shared fern' }] }] };
  expect(sitterView(feed, 'feed')).toEqual(expected);
  expect(sitterView({ identity: {}, plantCount: 1, tasks: { today: '2030-06-01', view: 'today', settings: {}, groups: {}, sections: [] }, feed, private: 'hidden' }, 'home'))
    .toEqual({ identity: {}, plantCount: 1, tasks: { today: '2030-06-01', view: 'today', settings: {}, groups: {}, sections: [] }, feed: expected });
});

test('sitter projections are selected by policy, never by response shape', () => {
  const payload = { display_name: 'Private name', batchId: 'private-batch', water: { status: 'due', dueOn: '2030-06-01', private: 'hidden' } };
  expect(sitterView(payload, 'schedule')).toEqual({ water: { status: 'due', dueOn: '2030-06-01', basedOn: [] }, customTasks: {} });
  expect(sitterView({ id: 'plant', nickname: 'Fern', private: 'hidden' }, 'plantHeader')).toEqual({ id: 'plant', nickname: 'Fern' });
  expect(() => sitterView(payload, undefined as never)).toThrow();
});

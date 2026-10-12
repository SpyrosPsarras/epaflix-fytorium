import { afterEach, expect, test, vi } from 'vitest';
import { authorizeRoute, sitterView } from '../../src/lib/server/policy';
import type { Identity } from '../../src/lib/server/auth';

// No database/configuration is needed for the deny-by-default branch.
vi.mock('../../src/lib/server/config', () => ({ config: () => ({}) }));
afterEach(() => vi.restoreAllMocks());

test.each(['owner', 'family', 'sitter', null] as const)('unlisted pages and non-API handlers refuse %s with 404 and name the missing route', async role => {
  const identity: Identity | null = role ? { member: { id: 'synthetic-member', role }, household: { id: 'synthetic-household', name: 'Test household' } } : null;
  const warning = vi.spyOn(console, 'warn').mockImplementation(() => {});
  for (const route of ['/export', '/api/export']) {
    for (const method of ['GET', 'HEAD', 'POST']) {
      await expect(authorizeRoute(identity, route, method, {})).rejects.toMatchObject({ status: 404 });
      expect(warning).toHaveBeenLastCalledWith(expect.stringContaining(route));
    }
  }
});

test('sitter projections are selected by policy, never by response shape', () => {
  const payload = { display_name: 'Private name', batchId: 'private-batch', water: { status: 'due', dueOn: '2030-06-01', private: 'hidden' } };
  expect(sitterView(payload, 'schedule')).toEqual({ water: { status: 'due', dueOn: '2030-06-01', basedOn: [] }, customTasks: {} });
  expect(sitterView({ id: 'plant', nickname: 'Fern', private: 'hidden' }, 'plantHeader')).toEqual({ id: 'plant', nickname: 'Fern' });
  expect(() => sitterView(payload, undefined as never)).toThrow();
});

import { EventEmitter } from 'node:events';
import { beforeEach, expect, test, vi } from 'vitest';
const fixture = vi.hoisted(() => ({ connect: vi.fn() }));
vi.mock('pg', () => ({ default: { Pool: class { connect = fixture.connect; on() {} } } }));
vi.mock('../../src/lib/server/config', () => ({ config: () => ({ databaseUrl: 'postgres://synthetic.invalid/test' }) }));
import { withTransaction } from '../../src/lib/server/db';

let client: EventEmitter & { query: ReturnType<typeof vi.fn>; release: ReturnType<typeof vi.fn> };
beforeEach(() => {
  client = Object.assign(new EventEmitter(), { query: vi.fn().mockResolvedValue({}), release: vi.fn() });
  fixture.connect.mockResolvedValue(client);
});
test('commits a successful callback and releases its client', async () => {
  expect(await withTransaction(async () => 'synthetic result')).toBe('synthetic result');
  expect(client.query.mock.calls).toEqual([['BEGIN'], ['COMMIT']]);
  expect(client.release.mock.calls).toEqual([[]]);
});
test('rolls back a callback failure without replacing the original error', async () => {
  const failure = new Error('synthetic failure');
  await expect(withTransaction(async () => { throw failure; })).rejects.toBe(failure);
  expect(client.query.mock.calls).toEqual([['BEGIN'], ['ROLLBACK']]);
  expect(client.release.mock.calls).toEqual([[]]);
});
test('discards a client whose rollback fails', async () => {
  client.query.mockImplementation(async (sql: string) => { if (sql === 'ROLLBACK') throw new Error('synthetic disconnect'); });
  const failure = new Error('synthetic failure');
  await expect(withTransaction(async () => { throw failure; })).rejects.toBe(failure);
  expect(client.release).toHaveBeenCalledWith(true);
});
test('preserves a COMMIT failure and discards the client even when rollback succeeds', async () => {
  const failure = new Error('synthetic commit failure');
  client.query.mockImplementation(async (sql: string) => { if (sql === 'COMMIT') throw failure; });
  await expect(withTransaction(async () => 'synthetic result')).rejects.toBe(failure);
  expect(client.query.mock.calls).toEqual([['BEGIN'], ['COMMIT'], ['ROLLBACK']]);
  expect(client.release.mock.calls).toEqual([[true]]);
  expect(client.listenerCount('error')).toBe(0);
});
test('handles a checked-out client error and discards it even if queries resolve', async () => {
  await expect(withTransaction(async () => { client.emit('error', new Error('synthetic backend termination')); })).rejects.toThrow('synthetic backend termination');
  expect(client.release).toHaveBeenCalledWith(true);
  expect(client.query.mock.calls).toEqual([['BEGIN'], ['ROLLBACK']]);
});

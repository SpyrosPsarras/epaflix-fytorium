import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { beforeEach, expect, test, vi } from 'vitest';
import type { Identity } from '../../src/lib/server/auth';
import { fixturePlant } from '../plant-fixture';
import { taskTypes } from '../../src/lib/schedule/engine';

const fixture = vi.hoisted(() => ({ connect: vi.fn(), query: vi.fn() }));
vi.mock('pg', () => ({ default: { Pool: class { connect = fixture.connect; query = fixture.query; on() {} } } }));
vi.mock('../../src/lib/server/config', () => ({ config: () => ({ householdTimezone: 'UTC', databaseUrl: 'postgres://synthetic.invalid/test' }) }));
import { complete, completeAll, completeType } from '../../src/lib/server/tasks';

const identity: Identity = { language: 'en', household: { id: randomUUID(), name: 'Synthetic household' }, member: { id: randomUUID(), role: 'owner' } };
const plant = { ...fixturePlant, id: randomUUID(), household_id: identity.household.id, site_id: randomUUID(), gbif_key: 910001,
  status: 'active', created_at: new Date('2020-01-01T12:00:00Z') };
const custom = { id: randomUUID(), household_id: identity.household.id, plant_id: plant.id, site_id: null, name: 'Synthetic inspection', interval_days: 1, created_at: plant.created_at, deleted_at: null };
let inTransaction: boolean;
let writes: unknown[][];
let lastWateredAt: Date | undefined;

beforeEach(() => {
  inTransaction = false;
  writes = [];
  lastWateredAt = undefined;
  const query = async (sql: string, values: unknown[] = []) => {
    let rows: unknown[] = [];
    if (sql === 'BEGIN') inTransaction = true;
    else if (sql === 'COMMIT' || sql === 'ROLLBACK') inTransaction = false;
    else if (sql.startsWith('SELECT p.id,')) rows = [plant];
    else if (sql.startsWith('SELECT * FROM care_events') && lastWateredAt) rows = [{ plant_id: plant.id, task_type: 'water', kind: 'completed', occurred_at: lastWateredAt }];
    else if (sql.startsWith('SELECT * FROM sites')) rows = [{ id: plant.site_id, location: 'outdoor', humidity: 'normal' }];
    else if (sql.startsWith('SELECT task_sort')) rows = [{ sort: 'task', filter: 'all', show_completed: false }];
    else if (sql.startsWith('SELECT latitude')) rows = [{ latitude: null }];
    else if (sql.startsWith('SELECT count(*)')) rows = [{ n: 1 }];
    else if (sql.startsWith('SELECT display_name')) rows = [{ display_name: 'Synthetic owner' }];
    else if (sql.includes('FROM custom_tasks')) rows = [custom];
    else if (sql.startsWith('INSERT INTO care_actions') || sql.startsWith('SELECT request_id FROM care_actions')) rows = [{ request_id: values[0] }];
    else if (sql.startsWith('SELECT b.request_id')) rows = [{ request_id: values[0], undone_at: null }];
    else if (sql.startsWith('INSERT INTO care_events')) {
      writes.push(values);
      rows = [{ id: randomUUID() }];
    }
    return { rows, rowCount: rows.length };
  };
  const client = Object.assign(new EventEmitter(), { query, release: vi.fn() });
  fixture.connect.mockResolvedValue(client);
  fixture.query.mockImplementation(async (sql: string, values: unknown[]) => {
    if (inTransaction) throw new Error('Pool query inside care transaction: ' + sql);
    return query(sql, values);
  });
});

test.each(['all', 'type', 'single', 'custom'] as const)('%s completion schedules and writes without a pool query during its transaction', async action => {
  const requestId = randomUUID();
  if (action === 'all') await completeAll(identity, plant.id, requestId);
  else if (action === 'single') await complete(identity, plant.id, 'water', requestId);
  else await completeType(identity, action === 'custom' ? custom.id.toUpperCase() : 'water', requestId);
  expect(inTransaction).toBe(false);
  expect(writes).toEqual(expect.arrayContaining([expect.arrayContaining([identity.household.id, plant.id, 'completed', identity.member.id])]));
  if (action === 'custom') expect(writes[0][10]).toBe(custom.id);
  else expect(writes.some(values => values[2] === 'water')).toBe(true);
});

test.each(['single', 'all', 'type'] as const)('%s liquid fertilizing records watering once in the same transaction', async action => {
  const requestId = randomUUID();
  if (action === 'all') await completeAll(identity, plant.id, requestId);
  else if (action === 'type') await completeType(identity, 'fertilize', requestId);
  else await complete(identity, plant.id, 'fertilize', requestId);
  const fertilize = writes.find(values => values[2] === 'fertilize')!;
  const water = writes.filter(values => values[2] === 'water');
  expect(fertilize).toBeDefined();
  expect(water).toHaveLength(1);
  expect(water[0][4]).toEqual(fertilize[4]);
  expect(water[0][8]).toBe(action === 'single' ? null : requestId);
  if (action !== 'all') expect(water[0][11]).toEqual(expect.any(String));
});

test.each([true, false])('handle all records water once with water due %s and fertilize first', async waterDue => {
  if (!waterDue) lastWateredAt = new Date();
  const order = [...taskTypes].reverse();
  const iterator = vi.spyOn(taskTypes, Symbol.iterator).mockImplementation(() => order.values());
  try {
    await completeAll(identity, plant.id, randomUUID());
    expect(writes.filter(values => values[2] === 'fertilize')).toHaveLength(1);
    const water = writes.filter(values => values[2] === 'water');
    expect(water).toHaveLength(1);
    expect(water[0][11]).toEqual(waterDue ? null : expect.any(String));
  } finally { iterator.mockRestore(); }
});

test.each(['snoozed', 'skipped'] as const)('%s fertilizing does not water', async kind => {
  await complete(identity, plant.id, 'fertilize', randomUUID(), kind);
  expect(writes.map(values => values[2])).toEqual(['fertilize']);
});

test.each(['sticks', 'slow_release'])('%s fertilizing does not water', async fertilizer => {
  plant.fertilizer_type = fertilizer;
  try {
    await complete(identity, plant.id, 'fertilize', randomUUID());
    expect(writes.map(values => values[2])).toEqual(['fertilize']);
  } finally { plant.fertilizer_type = 'liquid'; }
});

test('rejects uppercase built-in task types rather than completing lowercase tasks', async () => {
  await expect(completeType(identity, 'WATER', randomUUID())).rejects.toMatchObject({ status: 404 });
  expect(writes).toEqual([]);
});

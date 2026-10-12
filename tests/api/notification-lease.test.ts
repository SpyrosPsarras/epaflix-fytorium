import { afterAll, beforeAll, expect, test, vi } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
import { withNotificationLease } from '../../src/lib/server/notifications/lease';
// Direct lease callers supply their database and clock instead of SvelteKit runtime configuration.
vi.mock('../../src/lib/server/config', () => ({ config: () => { throw new Error('Unexpected runtime config read'); } }));

const harness = createApiHarness({ testClock: true });
let database: pg.Pool;
let memberId: string;
let member: Awaited<ReturnType<typeof harness.login>>;
beforeAll(async () => {
  member = await harness.login();
  memberId = (await (await member.request('/api/me')).json()).member.id;
  await member.request('/api/notifications/settings', 'PUT', { reminder_time: '09:00', reminders_enabled: true, weather_alerts_enabled: false });
  expect((await member.request('/api/notifications/subscriptions', 'POST', harness.push.subscribe())).status).toBe(201);
  database = new pg.Pool({ connectionString: harness.postgres.getConnectionUri() });
});
afterAll(async () => { await database?.end(); });

test('a notification lease admits one sender, doubles its expiry and gives up after six attempts', async () => {
  let at = new Date('2030-06-01T07:00:00Z');
  let calls = 0;
  const send = async () => { calls++; return { sent: 0 }; };
  const tick = () => withNotificationLease({ kind: 'daily', memberId, key: '2030-06-01' }, send, database, () => at);
  const warning = vi.spyOn(console, 'error').mockImplementation(() => {});
  try {
    await Promise.all([tick(), tick()]);
    expect(calls).toBe(1);
    for (const [index, minutes] of [5, 10, 20, 40, 80].entries()) {
      at = new Date(at.getTime() + minutes * 60000);
      await tick();
      expect(calls).toBe(index + 1);
      at = new Date(at.getTime() + 1);
      await Promise.all([tick(), tick()]);
      expect(calls).toBe(index + 2);
    }
    expect(warning).toHaveBeenCalledWith('Daily push gave up after 6 attempts.');
    at = new Date('2030-06-01T20:00:00Z');
    await tick();
    expect(calls).toBe(6);
  } finally { warning.mockRestore(); }
});

test('a successful lease stays sent, and all-expired devices record a skip rather than replaying', async () => {
  const at = () => new Date('2030-06-02T07:00:00Z');
  let calls = 0;
  const send = async () => { calls++; return { sent: 1 }; };
  const target = { kind: 'daily' as const, memberId, key: '2030-06-02' };
  await withNotificationLease(target, send, database, at);
  await withNotificationLease(target, send, database, () => new Date('2030-06-02T20:00:00Z'));
  expect(calls).toBe(1);
  const devices = await (await member.request('/api/notifications/subscriptions')).json();
  for (const device of devices) await member.request('/api/notifications/subscriptions/' + device.id, 'DELETE');
  const skipped = { ...target, key: '2030-06-03' };
  await withNotificationLease(skipped, async () => { calls++; return { sent: 0 }; }, database, () => new Date('2030-06-03T07:00:00Z'));
  await member.request('/api/notifications/subscriptions', 'POST', harness.push.subscribe());
  await withNotificationLease(skipped, send, database, () => new Date('2030-06-03T20:00:00Z'));
  expect(calls).toBe(2);
});

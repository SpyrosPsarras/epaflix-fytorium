import { expect, test } from 'vitest';
import pg from 'pg';
import { createApiHarness } from './harness';
const harness = createApiHarness();

test('a terminated request transaction returns 5xx and the next transaction still works', async () => {
  const { request } = await harness.login();
  const site = await (await request('/api/sites', 'POST', { name: 'Synthetic termination site', location: 'indoor', light: 'part_sun', grow_light: false, humidity: 'normal', draft: false })).json();
  const control = new pg.Client({ connectionString: harness.postgres.getConnectionUri() });
  await control.connect();
  try {
    await control.query('BEGIN');
    await control.query('SELECT id FROM sites WHERE id = $1 FOR UPDATE', [site.id]);
    const pending = request('/api/sites/' + site.id, 'DELETE').catch(cause => cause);
    let pid: number | undefined;
    for (let attempt = 0; attempt < 100; attempt++) {
      await control.query('SELECT pg_stat_clear_snapshot()');
      const waiting = await control.query("SELECT pid FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'DELETE FROM sites%' AND pid <> pg_backend_pid()");
      pid = waiting.rows[0]?.pid;
      if (pid) break;
      await new Promise(resolve => setTimeout(resolve, 20));
    }
    expect(pid).toEqual(expect.any(Number));
    await control.query('SELECT pg_terminate_backend($1)', [pid]);
    const response = await pending;
    expect(response).toBeInstanceOf(Response);
    expect(response.status).toBeGreaterThanOrEqual(500);
    await control.query('ROLLBACK');
    expect((await request('/api/sites/' + site.id, 'DELETE')).status).toBe(204);
    expect((await request('/api/sites/' + site.id)).status).toBe(404);
  } finally {
    await control.query('ROLLBACK');
    await control.end();
  }
});

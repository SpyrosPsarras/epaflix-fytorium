import { expect, test } from 'vitest';
import { createApiHarness } from './harness';

const harness = createApiHarness({ testClock: true, appEnv: { SERVER_VERSION: 'synthetic-sha', HOUSEHOLD_TIMEZONE: 'Europe/Oslo' } });
test('meta requires login and reports only the household date, timezone, role and image versions', async () => {
  const anonymous = await fetch(harness.base + '/api/meta');
  expect(anonymous.status).toBe(401);
  await fetch(harness.base + '/__test/clock', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ action: 'set', at: '2030-06-01T23:30:00Z' }) });
  const owner = await harness.login();
  expect(await (await owner.request('/api/meta')).json()).toEqual({ minAppVersion: null, serverVersion: 'synthetic-sha', today: '2030-06-02', timezone: 'Europe/Oslo', role: 'owner' });
  for (const role of ['family', 'sitter']) {
    const invite = await (await owner.request('/api/household/invites', 'POST', { role, plant_ids: [] })).json();
    const member = await harness.login('meta-' + role, {}, invite.link.split('/').at(-1));
    for (const headers of [new Headers({ cookie: member.cookie }), new Headers({ authorization: 'Bearer ' + (await harness.appDevice('meta-' + role)).access_token })]) {
      const response = await fetch(harness.base + '/api/meta', { headers });
      expect(response.status).toBe(200);
      expect(await response.json()).toEqual({ minAppVersion: null, serverVersion: 'synthetic-sha', today: '2030-06-02', timezone: 'Europe/Oslo', role });
    }
  }
  await harness.restartApp({ SERVER_VERSION: '' });
  expect((await (await owner.request('/api/meta')).json()).serverVersion).toBe('dev');
});

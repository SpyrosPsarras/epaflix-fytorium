import { readdirSync, readFileSync } from 'node:fs';
import { expect, test } from 'vitest';
import { createApiHarness } from './harness';

const harness = createApiHarness({ appEnv: { MIN_APP_VERSION: '1.10.0' } });
test('every API handler refuses old apps, except meta and app authentication, without changing web requests', async () => {
  const owner = await harness.login();
  const device = await harness.appDevice();
  const files = readdirSync('src/routes/api', { recursive: true }).filter((file): file is string => typeof file === 'string' && file.endsWith('+server.ts'));
  for (const file of files) {
    const path = '/api/' + file.replace('/+server.ts', '').replace(/\[[^\]]+\]/g, '00000000-0000-4000-8000-000000000000');
    if (path === '/api/meta' || path.startsWith('/api/app/')) continue;
    const methods = [...readFileSync('src/routes/api/' + file, 'utf8').matchAll(/export const (GET|POST|PUT|DELETE|PATCH)\b/g)].map(match => match[1]);
    for (const method of methods) {
      const response = await fetch(harness.base + path, { method, headers: { authorization: 'Bearer ' + device.access_token, 'X-App-Version': '1.9.9' } });
      expect(response.status, method + ' ' + path).toBe(426);
      expect(await response.json()).toEqual({ error: 'update_required', minAppVersion: '1.10.0' });
    }
  }
  for (const version of ['', 'garbage', '1.10', '01.10.0', '1.10.0-rc.1', '1.9.99']) {
    const response = await fetch(harness.base + '/api/me', { headers: { cookie: owner.cookie, 'X-App-Version': version } });
    expect(response.status, version).toBe(426);
  }
  for (const version of ['1.10.0', '1.10.0+build.1', '1.11.0', '2.0.0']) {
    expect((await fetch(harness.base + '/api/me', { headers: { cookie: owner.cookie, 'X-App-Version': version } })).status).toBe(200);
  }
  expect((await owner.request('/api/me')).status).toBe(200);
  const meta = await fetch(harness.base + '/api/meta', { headers: { cookie: owner.cookie, 'X-App-Version': 'bad' } });
  expect(meta.status).toBe(200);
  expect((await meta.json()).minAppVersion).toBe('1.10.0');
  expect((await fetch(harness.base + '/api/app/token', { method: 'POST', headers: { 'X-App-Version': 'bad', 'content-type': 'application/json' }, body: '{}' })).status).toBe(400);
  expect((await fetch(harness.base + '/api/app/logout', { method: 'POST', headers: { authorization: 'Bearer ' + device.access_token, 'X-App-Version': 'bad' } })).status).toBe(204);
});

test('unset minimum never rejects a version header, and invalid configuration fails startup clearly', async () => {
  await harness.restartApp({ MIN_APP_VERSION: '' });
  const owner = await harness.login();
  expect((await fetch(harness.base + '/api/me', { headers: { cookie: owner.cookie, 'X-App-Version': 'bad' } })).status).toBe(200);
  expect((await (await owner.request('/api/meta')).json()).minAppVersion).toBeNull();
  await expect(harness.restartApp({ MIN_APP_VERSION: '1.2' })).rejects.toThrow();
  expect(harness.appOutput).toContain('Invalid configuration: MIN_APP_VERSION');
});

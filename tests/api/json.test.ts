import { expect, test } from 'vitest';
import { createApiHarness } from './harness';
const harness = createApiHarness();
test('site, plant and task settings JSON endpoints reject oversized bodies with 413', async () => {
  const { request } = await harness.login();
  for (const [path, method] of [['/api/sites', 'POST'], ['/api/plants', 'POST'], ['/api/tasks/settings', 'PUT']]) {
    const response = await request(path, method, { synthetic: 'x'.repeat(65536) });
    expect(response.status, path).toBe(413);
  }
  expect((await request('/api/sites')).status).toBe(200);
});

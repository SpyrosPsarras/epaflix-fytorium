import { readdirSync, readFileSync } from 'node:fs';
import { expect, test } from 'vitest';
import { createApiHarness } from './harness';
import { assertApiResponse, spec } from './openapi';

const harness = createApiHarness();
test('the OpenAPI 3.1 contract describes every API handler', () => {
  expect(spec.openapi).toBe('3.1.0');
  const actual = readdirSync('src/routes/api', { recursive: true }).filter((file): file is string => typeof file === 'string' && file.endsWith('+server.ts'))
    .flatMap(file => [...readFileSync('src/routes/api/' + file, 'utf8').matchAll(/export const (GET|POST|PUT|DELETE|PATCH)\b/g)]
      .map(match => match[1].toLowerCase() + ' /api/' + file.replace('/+server.ts', '').replace(/\[([^\]]+)\]/g, '{$1}'))).sort();
  const documented = Object.entries(spec.paths).flatMap(([path, operations]) => Object.keys(operations).filter(method => ['get', 'post', 'put', 'delete', 'patch'].includes(method)).map(method => method + ' ' + path)).sort();
  expect(documented).toEqual(actual);
});
test('real responses validate, while missing fields, changed types and extra meta fields fail', async () => {
  const owner = await harness.login();
  const response = await owner.request('/api/meta');
  await expect(assertApiResponse('GET', response.url, response)).resolves.toBeUndefined();
  const body = await response.json();
  for (const changed of [{ ...body, today: 12 }, { ...body, role: 'stranger' }, { ...body, privateField: 'no' }, Object.fromEntries(Object.entries(body).filter(([key]) => key !== 'timezone'))]) {
    await expect(assertApiResponse('GET', harness.base + '/api/meta', new Response(JSON.stringify(changed), { headers: { 'content-type': 'application/json' } }))).rejects.toThrow('OpenAPI response mismatch');
  }
});

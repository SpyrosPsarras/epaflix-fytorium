import { afterAll, beforeAll, expect, test } from 'vitest';
import { createPushService } from '../push-service.mjs';
import { FcmTransport, FcmError } from '../../src/lib/server/notifications/fcm';

const fake = createPushService();
let transport: FcmTransport;
let base: string;
beforeAll(async () => {
  await new Promise<void>(resolve => fake.server.listen(0, '127.0.0.1', resolve));
  const address = fake.server.address();
  if (!address || typeof address === 'string') throw new Error('No fake address');
  base = `http://127.0.0.1:${address.port}`;
  transport = new FcmTransport({ baseUrl: base, tokenUrl: base + '/oauth/token', projectId: 'synthetic-fytorium', account: fake.fcm.account });
});
afterAll(async () => { fake.server.closeAllConnections(); await new Promise<void>(resolve => fake.server.close(() => resolve())); });
const payload = { title: 'Fytorium', body: '1 task today', tag: 'fytorium-daily', url: '/' as const };

test('HTTP v1 sends exactly the shared data payload and shares one cached OAuth token across concurrent sends', async () => {
  await Promise.all(['synthetic-one', 'synthetic-two'].map(token => transport.send(token, payload)));
  expect(fake.fcm.received).toEqual(['synthetic-one', 'synthetic-two'].map(token => ({ message: { token, data: payload } })));
  expect(fake.fcm.oauthCalls).toBe(1);
  await transport.send('synthetic-three', payload);
  expect(fake.fcm.oauthCalls).toBe(1);
});

test('only unregistered and token-specific invalid arguments remove tokens; transient errors remain retryable', async () => {
  for (const [status, code, permanent] of [[404, 'UNREGISTERED', true], [400, 'INVALID_ARGUMENT', true], [400, 'BAD_REQUEST', false], [429, 'QUOTA_EXCEEDED', false], [503, 'UNAVAILABLE', false]] as const) {
    expect((await fetch(base + '/fcm/respond', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ token: 'synthetic-error', status, code }) })).status).toBe(200);
    await expect(transport.send('synthetic-error', payload)).rejects.toMatchObject({ permanent, statusCode: status });
  }
  expect(new FcmError(404, 'UNREGISTERED').message).not.toContain('synthetic');
});

test('expired access tokens are renewed', async () => {
  const address = fake.server.address();
  if (!address || typeof address === 'string') throw new Error('No fake address');
  const base = `http://127.0.0.1:${address.port}`;
  let at = 0;
  const cached = new FcmTransport({ baseUrl: base, tokenUrl: base + '/oauth/token', projectId: 'synthetic-fytorium', account: fake.fcm.account }, () => at);
  const before = fake.fcm.oauthCalls;
  await cached.send('synthetic-expiry', payload);
  at = 3500_000;
  await cached.send('synthetic-expiry', payload);
  expect(fake.fcm.oauthCalls).toBe(before + 1);
  at = 3600_001;
  await cached.send('synthetic-expiry', payload);
  expect(fake.fcm.oauthCalls).toBe(before + 2);
});

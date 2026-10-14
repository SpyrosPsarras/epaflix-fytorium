import { afterAll, beforeAll, expect, test, vi } from 'vitest';
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
  at = 3540_000;
  await cached.send('synthetic-expiry', payload);
  expect(fake.fcm.oauthCalls).toBe(before + 2);
});

test('bare send 404 is reported once and never removes a registration', async () => {
  const wrongProject = new FcmTransport({ baseUrl: base, tokenUrl: base + '/oauth/token', projectId: 'missing-project', account: fake.fcm.account });
  const log = vi.spyOn(console, 'error').mockImplementation(() => {});
  try {
    for (let i = 0; i < 2; i++) await expect(wrongProject.send('synthetic-kept', payload)).rejects.toMatchObject({ statusCode: 404, permanent: false, misconfigured: true });
    expect(log).toHaveBeenCalledTimes(1);
    expect(log.mock.calls[0][0]).not.toContain('synthetic-kept');
  } finally { log.mockRestore(); }
});

test('INVALID_ARGUMENT requires an FCM detail and clear registration-token evidence', async () => {
  const fcm = { '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'INVALID_ARGUMENT' };
  const bad = (field: string) => ({ '@type': 'type.googleapis.com/google.rpc.BadRequest', fieldViolations: [{ field }] });
  for (const [details, message, permanent] of [
    [[fcm], 'Invalid payload.', false],
    [[fcm], 'The registration token is not valid.', true],
    [[fcm, bad('message.data')], 'The registration token is not valid.', false],
    [[bad('message.token')], 'The registration token is not valid.', false],
    [[fcm, bad('message.token')], 'Invalid argument.', true],
  ] as const) {
    const mock = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({ error: { message, details } }), { status: 400 }));
    try { await expect(transport.send('synthetic-detail', payload)).rejects.toMatchObject({ permanent }); }
    finally { mock.mockRestore(); }
  }
});

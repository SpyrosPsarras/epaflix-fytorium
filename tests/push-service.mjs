import { createServer } from 'node:http';
import { createECDH, generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import webPush from 'web-push';
import ece from 'http_ece';
import { importJWK, jwtVerify } from 'jose';

// Synthetic subscriptions never resolve to a real push provider.
export function createPushService() {
  const vapid = webPush.generateVAPIDKeys();
  const devices = new Map();
  const received = [];
  const attempted = [];
  // Ephemeral, synthetic service account. Never persisted to the checkout or image.
  const pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { client_email: 'synthetic-fcm@example.test', private_key: pair.privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  const accessToken = randomBytes(32).toString('base64url');
  const fcm = { account, received: [], attempted: [], oauthCalls: 0,
    respond(token, status, code) { fcmStatuses.set(token, { status, code }); } };
  const fcmStatuses = new Map();
  function subscribe() {
    const id = randomUUID();
    const dh = createECDH('prime256v1');
    dh.generateKeys();
    const auth = randomBytes(16).toString('base64url');
    const subscription = { endpoint: `https://push.invalid/send/${id}`, keys: { p256dh: dh.getPublicKey().toString('base64url'), auth } };
    devices.set(id, { dh, auth, status: 201, delay: 0, bodyBytes: 0 });
    return subscription;
  }
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://fake');
    response.setHeader('content-type', 'application/json');
    if (url.pathname === '/fcm/account') return response.end(JSON.stringify(account));
    if (url.pathname === '/fcm/respond' && request.method === 'POST') {
      try {
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        const { token, status, code } = JSON.parse(Buffer.concat(chunks).toString());
        if (typeof token !== 'string' || ![200, 400, 404, 429, 500, 503].includes(status) || typeof code !== 'string') throw new Error();
        fcm.respond(token, status, code);
        return response.end('{}');
      } catch { return response.writeHead(400).end('{}'); }
    }
    if (url.pathname === '/fcm/received') return response.end(JSON.stringify(fcm.received));
    if (url.pathname === '/fcm/stats') return response.end(JSON.stringify({ oauthCalls: fcm.oauthCalls, attempts: fcm.attempted.length }));
    if (url.pathname === '/oauth/token' && request.method === 'POST') {
      try {
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        const params = new URLSearchParams(Buffer.concat(chunks).toString());
        if (params.get('grant_type') !== 'urn:ietf:params:oauth:grant-type:jwt-bearer') throw new Error();
        await jwtVerify(params.get('assertion'), pair.publicKey, { algorithms: ['RS256'], issuer: account.client_email,
          audience: `http://${request.headers.host}/oauth/token` });
        fcm.oauthCalls++;
        return response.end(JSON.stringify({ access_token: accessToken, expires_in: 3600, token_type: 'Bearer' }));
      } catch { return response.writeHead(400).end('{}'); }
    }
    if (url.pathname === '/v1/projects/synthetic-fytorium/messages:send' && request.method === 'POST') {
      if (request.headers.authorization !== `Bearer ${accessToken}`) return response.writeHead(401).end('{}');
      try {
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        const message = JSON.parse(Buffer.concat(chunks).toString());
        if (!message.message?.token || !message.message?.data || message.message.notification) throw new Error();
        fcm.attempted.push(message);
        const { status = 200, code } = fcmStatuses.get(message.message.token) ?? {};
        if (status === 200) fcm.received.push(message);
        return response.writeHead(status).end(JSON.stringify(status === 200 ? { name: 'synthetic-message' } : {
          error: { message: code === 'INVALID_ARGUMENT' ? 'The registration token is not valid.' : 'Synthetic error', details: [{ '@type': code === 'BAD_REQUEST' ? 'type.googleapis.com/google.rpc.BadRequest' : 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: code }] },
        }));
      } catch { return response.writeHead(400).end('{}'); }
    }
    if (url.pathname === '/vapid') return response.end(JSON.stringify(vapid));
    if (url.pathname === '/subscriptions' && request.method === 'POST') return response.end(JSON.stringify(subscribe()));
    if (url.pathname === '/received') return response.end(JSON.stringify(received));
    if (request.method !== 'POST' || !url.pathname.startsWith('/send/')) return response.writeHead(404).end();
    const device = devices.get(url.pathname.split('/').pop());
    if (!device) return response.writeHead(410).end();
    try {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      if (request.headers['content-encoding'] !== 'aes128gcm' || !request.headers.authorization?.startsWith('vapid ')) throw new Error('Expected encrypted VAPID push');
      const authorization = /^vapid t=([^,]+), k=([A-Za-z0-9_-]+)$/.exec(request.headers.authorization);
      if (!authorization || authorization[2] !== vapid.publicKey) throw new Error('Wrong VAPID key');
      const publicKey = Buffer.from(vapid.publicKey, 'base64url');
      const key = await importJWK({ kty: 'EC', crv: 'P-256', x: publicKey.subarray(1, 33).toString('base64url'), y: publicKey.subarray(33).toString('base64url') }, 'ES256');
      const verified = await jwtVerify(authorization[1], key, { audience: 'https://push.invalid', algorithms: ['ES256'] });
      if (verified.payload.sub !== 'mailto:push@example.test') throw new Error('Wrong VAPID subject');
      const plaintext = ece.decrypt(Buffer.concat(chunks), { version: 'aes128gcm', privateKey: device.dh, authSecret: device.auth });
      attempted.push({ endpoint: `https://push.invalid${url.pathname}`, payload: JSON.parse(plaintext.toString()) });
      if (device.status === 201) received.push({ endpoint: `https://push.invalid${url.pathname}`, payload: JSON.parse(plaintext.toString()) });
      if (device.bodyBytes) {
        response.writeHead(device.status);
        response.write(Buffer.alloc(device.bodyBytes));
      }
      const timer = setTimeout(() => {
        if (!response.headersSent) response.writeHead(device.status);
        response.end();
      }, device.delay);
      response.on('close', () => clearTimeout(timer));
    } catch { response.writeHead(400).end(); }
  });
  return { server, vapid, received, attempted, subscribe, fcm,
    respond(endpoint, status, delay = 0, bodyBytes = 0) { Object.assign(devices.get(endpoint.split('/').pop()), { status, delay, bodyBytes }); },
  };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createPushService().server.listen(8080, '0.0.0.0');
}

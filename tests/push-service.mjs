import { createServer } from 'node:http';
import { createECDH, randomBytes, randomUUID } from 'node:crypto';
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
  return { server, vapid, received, attempted, subscribe,
    respond(endpoint, status, delay = 0, bodyBytes = 0) { Object.assign(devices.get(endpoint.split('/').pop()), { status, delay, bodyBytes }); },
  };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createPushService().server.listen(8080, '0.0.0.0');
}

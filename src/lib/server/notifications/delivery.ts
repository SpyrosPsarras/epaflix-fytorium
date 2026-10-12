import { Agent, request as httpsRequest } from 'node:https';
import { request as httpRequest } from 'node:http';
import webPush from 'web-push';
import { config } from '../config';
import { now } from '../clock';
import { db } from '../db';
import { publicAddress, pinnedLookup, publicUrlLimits } from '../net/public-url';
import { syntheticPush } from './subscriptions';

export type PushPayload = { title: string; body: string; tag: string; url: '/' };
export const leaseMs = 5 * 60 * 1000;

class PushResponseError extends Error {
  constructor(readonly statusCode: number) {
    super('Push endpoint refused the notification.');
    this.name = 'PushResponseError';
  }
}

async function deliver(subscription: { endpoint: string; keys: { p256dh: string; auth: string } }, payload: PushPayload) {
  const settings = config();
  const details = webPush.generateRequestDetails(subscription, JSON.stringify(payload), {
    vapidDetails: { subject: settings.vapidSubject, publicKey: settings.vapidPublicKey, privateKey: settings.vapidPrivateKey },
    contentEncoding: 'aes128gcm', TTL: 3600,
  });
  const endpoint = new URL(subscription.endpoint);
  const signal = AbortSignal.timeout(publicUrlLimits.timeoutMs);
  let target = endpoint;
  let agent: Agent | undefined;
  if (syntheticPush(endpoint)) target = new URL(endpoint.pathname, settings.testPushUrl!);
  else {
    const address = await publicAddress(endpoint, { signal });
    agent = new Agent({ lookup: pinnedLookup(address) });
  }
  try {
    await new Promise<void>((resolve, reject) => {
      const request = (target.protocol === 'https:' ? httpsRequest : httpRequest)(target, {
        method: details.method, headers: details.headers, agent,
        signal,
      }, response => {
        const status = response.statusCode ?? 502;
        // Only the status matters. Read zero body bytes instead of buffering web-push responseText.
        response.destroy();
        if (status >= 200 && status < 300) resolve();
        else reject(new PushResponseError(status));
      });
      request.on('error', reject);
      request.end(details.body);
    });
  } finally { agent?.destroy(); }
}

export async function sendToMember(member: string, payload: PushPayload) {
  const { rows } = await db().query('SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE member_id = $1', [member]);
  let cursor = 0, sent = 0;
  await Promise.all(Array.from({ length: Math.min(4, rows.length) }, async () => {
    while (cursor < rows.length) {
      const row = rows[cursor++];
      try {
        await deliver({ endpoint: row.endpoint, keys: { p256dh: row.p256dh, auth: row.auth } }, payload);
        sent++;
        await db().query('UPDATE push_subscriptions SET last_success_at = $2 WHERE id = $1', [row.id, now()]);
      } catch (cause) {
        const status = cause instanceof PushResponseError ? cause.statusCode : undefined;
        if (status === 404 || status === 410) await db().query('DELETE FROM push_subscriptions WHERE id = $1', [row.id]);
        else console.error('Push delivery failed. No immediate retry.');
      }
    }
  }));
  return { sent };
}

import { Agent, request as httpsRequest } from 'node:https';
import { request as httpRequest } from 'node:http';
import webPush from 'web-push';
import { config } from '../config';
import { now } from '../clock';
import { db } from '../db';
import { publicAddress, pinnedLookup, publicUrlLimits } from '../net/public-url';
import { syntheticPush } from './subscriptions';
import { FcmError } from './fcm';
import { fcmConfigured, fcmTransport } from './fcm-config';

export type PushPayload = { title: string; body: string; tag: string; url: '/' };
export const leaseMs = 5 * 60 * 1000;

// Only claim a lease when this member has a device we can actually deliver to.
export function availableDeviceSql(member: string, at: string) {
  return `EXISTS (SELECT 1 FROM push_subscriptions s LEFT JOIN app_devices d ON d.id = s.app_device_id
    WHERE s.member_id = ${member} AND s.enabled AND (
      (s.transport = 'web' AND ${!!config().vapidPublicKey}) OR
      (s.transport = 'fcm' AND ${fcmConfigured()} AND d.revoked_at IS NULL AND d.last_used_at > ${at}::timestamptz - INTERVAL '90 days')))`;
}

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
  const { rows } = await db().query(`SELECT s.id, s.transport, s.fcm_token, s.endpoint, s.p256dh, s.auth FROM push_subscriptions s
    LEFT JOIN app_devices d ON d.id = s.app_device_id
    WHERE s.member_id = $1 AND s.enabled AND (s.transport = 'web' OR (d.revoked_at IS NULL AND d.last_used_at > $2))`, [member, new Date(now().getTime() - 90 * 86400000)]);
  let cursor = 0, sent = 0;
  await Promise.all(Array.from({ length: Math.min(4, rows.length) }, async () => {
    while (cursor < rows.length) {
      const row = rows[cursor++];
      try {
        if (row.transport === 'fcm') {
          const fcm = fcmTransport();
          if (!fcm) continue;
          await fcm.send(row.fcm_token, payload);
        } else {
          if (!config().vapidPublicKey) continue;
          await deliver({ endpoint: row.endpoint, keys: { p256dh: row.p256dh, auth: row.auth } }, payload);
        }
        sent++;
        await db().query('UPDATE push_subscriptions SET last_success_at = $2 WHERE id = $1', [row.id, now()]);
      } catch (cause) {
        const status = cause instanceof PushResponseError ? cause.statusCode : undefined;
        if (status === 404 || status === 410 || cause instanceof FcmError && cause.permanent) await db().query('DELETE FROM push_subscriptions WHERE id = $1', [row.id]);
        else if (!(cause instanceof FcmError && cause.misconfigured)) console.error('Push delivery failed. No immediate retry.');
      }
    }
  }));
  return { sent };
}

import { error } from '@sveltejs/kit';
import { ECDH } from 'node:crypto';
import type { Identity } from '../auth';
import { now } from '../clock';
import { config } from '../config';
import { db, withTransaction } from '../db';
import { publicAddress } from '../net/public-url';
import { isUuid } from '../plants';

export function syntheticPush(url: URL) {
  return !!config().testPushUrl && url.origin === 'https://push.invalid';
}
export async function subscriptions(identity: Identity, deviceId?: string) {
  const rows = await db().query('SELECT s.id, s.label, s.transport, s.enabled, s.created_at, s.last_success_at, s.app_device_id FROM push_subscriptions s JOIN members m ON m.id = s.member_id WHERE m.id = $1 AND m.household_id = $2 ORDER BY s.created_at, s.id', [identity.member.id, identity.household.id]);
  return rows.rows.map(({ app_device_id, ...row }) => ({ ...row, ...(deviceId ? { current_device: app_device_id === deviceId } : {}) }));
}
function key(value: unknown, length: number) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value)) error(422, 'Invalid subscription keys');
  const decoded = Buffer.from(value, 'base64url');
  if (decoded.length !== length || decoded.toString('base64url') !== value) error(422, 'Invalid subscription keys');
  return decoded;
}
export async function subscribe(identity: Identity, input: Record<string, unknown>, userAgent: string) {
  let url: URL;
  try {
    if (typeof input.endpoint !== 'string' || input.endpoint.length > 2048) throw new Error();
    url = new URL(input.endpoint);
    if (url.protocol !== 'https:' || url.username || url.password || url.hash) throw new Error();
    if (!syntheticPush(url)) await publicAddress(url);
  } catch { error(422, 'Use an HTTPS push endpoint on a public host'); }
  const keys = input.keys as Record<string, unknown> | undefined;
  const p256dh = key(keys?.p256dh, 65);
  key(keys?.auth, 16);
  try { ECDH.convertKey(p256dh, 'prime256v1'); } catch { error(422, 'Invalid subscription public key'); }
  const label = input.label ?? (userAgent.slice(0, 200) || 'Device');
  if (typeof label !== 'string' || !label.trim() || label.length > 200) error(422, 'Device label must be 1–200 characters');
  // An endpoint is globally unique, but must never be transferred to another member.
  const result = await db().query('INSERT INTO push_subscriptions (member_id, endpoint, p256dh, auth, label, created_at) VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (endpoint) DO UPDATE SET p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, label = EXCLUDED.label WHERE push_subscriptions.member_id = EXCLUDED.member_id RETURNING id', [identity.member.id, url.href, keys!.p256dh, keys!.auth, label.trim(), now()]);
  if (!result.rowCount) error(409, 'Subscription is already registered');
  return (await subscriptions(identity)).find(row => row.id === result.rows[0].id);
}
export async function registerFcm(identity: Identity, deviceId: string | undefined, input: Record<string, unknown>) {
  if (!deviceId) error(403, 'App bearer authentication required');
  if (typeof input.token !== 'string' || !/^[A-Za-z0-9_:.\-]{1,4096}$/.test(input.token)) error(422, 'Invalid FCM token');
  const token = input.token;
  const id = await withTransaction(async client => {
    const device = (await client.query('SELECT name FROM app_devices WHERE id = $1 AND member_id = $2 AND revoked_at IS NULL FOR UPDATE', [deviceId, identity.member.id])).rows[0];
    if (!device) error(403, 'App bearer authentication required');
    const existing = (await client.query('SELECT app_device_id FROM push_subscriptions WHERE fcm_token = $1', [token])).rows[0];
    if (existing && existing.app_device_id !== deviceId) error(409, 'Token is already registered');
    try {
      return (await client.query(`INSERT INTO push_subscriptions (member_id, transport, fcm_token, app_device_id, label, created_at)
        VALUES ($1, 'fcm', $2, $3, $4, $5) ON CONFLICT (app_device_id) DO UPDATE SET fcm_token = EXCLUDED.fcm_token RETURNING id`,
      [identity.member.id, token, deviceId, device.name, now()])).rows[0].id;
    } catch (cause) {
      if (cause && typeof cause === 'object' && 'code' in cause && cause.code === '23505') error(409, 'Token is already registered');
      throw cause;
    }
  });
  return (await subscriptions(identity)).find(row => row.id === id);
}
export async function switchSubscription(identity: Identity, id: string, enabled: unknown) {
  if (!isUuid(id)) error(404, 'Device not found');
  if (typeof enabled !== 'boolean') error(422, 'Choose an on/off switch');
  const result = await db().query('UPDATE push_subscriptions s SET enabled = $4 FROM members m WHERE s.member_id = m.id AND s.id = $1 AND m.id = $2 AND m.household_id = $3 RETURNING s.id', [id, identity.member.id, identity.household.id, enabled]);
  if (!result.rowCount) error(404, 'Device not found');
  return (await subscriptions(identity)).find(row => row.id === id);
}
export async function unsubscribe(identity: Identity, id: string) {
  if (!isUuid(id)) error(404, 'Device not found');
  const result = await db().query('DELETE FROM push_subscriptions s USING members m WHERE s.member_id = m.id AND m.id = $1 AND m.household_id = $2 AND s.id = $3', [identity.member.id, identity.household.id, id]);
  if (!result.rowCount) error(404, 'Device not found');
}

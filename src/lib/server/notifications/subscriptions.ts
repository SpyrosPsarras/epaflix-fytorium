import { error } from '@sveltejs/kit';
import { ECDH } from 'node:crypto';
import type { Identity } from '../auth';
import { now } from '../clock';
import { config } from '../config';
import { db } from '../db';
import { publicAddress } from '../net/public-url';
import { isUuid } from '../plants';

export function syntheticPush(url: URL) {
  return !!config().testPushUrl && url.origin === 'https://push.invalid';
}
export async function subscriptions(identity: Identity) {
  const rows = await db().query('SELECT s.id, s.label, s.created_at, s.last_success_at FROM push_subscriptions s JOIN members m ON m.id = s.member_id WHERE m.id = $1 AND m.household_id = $2 ORDER BY s.created_at, s.id', [identity.member.id, identity.household.id]);
  return rows.rows;
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
export async function unsubscribe(identity: Identity, id: string) {
  if (!isUuid(id)) error(404, 'Device not found');
  const result = await db().query('DELETE FROM push_subscriptions s USING members m WHERE s.member_id = m.id AND m.id = $1 AND m.household_id = $2 AND s.id = $3', [identity.member.id, identity.household.id, id]);
  if (!result.rowCount) error(404, 'Device not found');
}

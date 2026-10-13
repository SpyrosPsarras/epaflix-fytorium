import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { error } from '@sveltejs/kit';
import type pg from 'pg';
import type { Identity } from './auth';
import { db, withTransaction } from './db';
import { now } from './clock';
import { limitAppTokens } from './app-rate-limit';

const REDIRECT_URI = 'com.epaflix.fytorium:/oauth2redirect';
const IDLE_MS = 90 * 86400000;
const USE_WRITE_MS = 3600000;
const RETRY_MS = 120000;
const MAX_GRACE_ISSUES = 5;
// shortcut: unflushed activity can lose up to one hour of idle credit on process failure.
const recentUse = new Map<string, Date>();
function lastUse(deviceId: string, stored: Date): Date {
  const recent = recentUse.get(deviceId);
  return recent && recent > stored ? recent : stored;
}
async function recordUse(client: Pick<pg.PoolClient, 'query'>, deviceId: string, stored: Date, time: Date) {
  recentUse.set(deviceId, lastUse(deviceId, time));
  if (stored.getTime() >= time.getTime() - USE_WRITE_MS) return;
  await client.query('UPDATE app_devices SET last_used_at = GREATEST(last_used_at, $2) WHERE id = $1 AND last_used_at < $3',
    [deviceId, time, new Date(time.getTime() - USE_WRITE_MS)]);
  if (recentUse.get(deviceId)! <= time) recentUse.delete(deviceId);
}

export async function maintainAppTokens() {
  const time = now();
  for (const [deviceId, usedAt] of recentUse) {
    const result = await db().query('UPDATE app_devices SET last_used_at = $2 WHERE id = $1 AND last_used_at < $3 AND last_used_at < $2 RETURNING id',
      [deviceId, usedAt, new Date(time.getTime() - USE_WRITE_MS)]);
    if ((result.rowCount || usedAt.getTime() <= time.getTime() - USE_WRITE_MS) && recentUse.get(deviceId) === usedAt) recentUse.delete(deviceId);
  }
  const cutoff = new Date(time.getTime() - IDLE_MS);
  await db().query('DELETE FROM app_refresh_tokens WHERE used_at < $1', [cutoff]);
  await db().query('DELETE FROM app_access_tokens WHERE expires_at < $1', [cutoff]);
}
let maintenanceStarted = false;
export function startAppTokenMaintenance() {
  if (maintenanceStarted) return;
  maintenanceStarted = true;
  let running = false;
  setInterval(async () => {
    if (running) return;
    running = true;
    try { await maintainAppTokens(); }
    catch { console.error('App token maintenance failed; retrying on the next tick.'); }
    finally { running = false; }
  }, 60000).unref();
}

const randomToken = () => randomBytes(32).toString('base64url');
const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const validToken = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value);
type AppLogin = { challenge: string; state: string };

export function appLoginParameters(url: URL): AppLogin | undefined {
  const params = url.searchParams;
  if (!params.has('app') && !['redirect_uri', 'code_challenge', 'code_challenge_method', 'state'].some(key => params.has(key))) return;
  if (params.get('app') !== 'android' || params.get('redirect_uri') !== REDIRECT_URI
    || params.get('code_challenge_method') !== 'S256' || !validToken(params.get('code_challenge'))
    || !params.get('state') || params.get('state')!.length > 1024 || params.get('state')!.includes('\0')
    || [...params.keys()].some(key => params.getAll(key).length !== 1)) error(400, 'Invalid app login');
  return { challenge: params.get('code_challenge')!, state: params.get('state')! };
}

export function appRedirect(state: string, result: { code: string } | { error: 'access_denied' | 'not_member' | 'server_error' }): string {
  const url = new URL(REDIRECT_URI);
  for (const [key, value] of Object.entries(result)) url.searchParams.set(key, value);
  url.searchParams.set('state', state);
  return url.href;
}

export class AppLoginFailure extends Error {
  constructor(public readonly location: string, public readonly code: 'access_denied' | 'server_error', cause: unknown) {
    super('App login failed', { cause });
    this.name = 'AppLoginFailure';
  }
}

export async function issueAppCode(client: pg.PoolClient, memberId: string, app: AppLogin): Promise<string> {
  const code = randomToken();
  await client.query('DELETE FROM app_login_codes WHERE expires_at <= $1', [now()]);
  await client.query('INSERT INTO app_login_codes (code_hash, member_id, challenge, expires_at) VALUES ($1,$2,$3,$4)',
    [hash(code), memberId, app.challenge, new Date(now().getTime() + 60000)]);
  return appRedirect(app.state, { code });
}

async function issuePair(client: pg.PoolClient, deviceId: string) {
  const access = randomToken(), refresh = randomToken();
  await client.query('INSERT INTO app_access_tokens (token_hash, device_id, expires_at, refresh_hash) VALUES ($1,$2,$3,$4)',
    [hash(access), deviceId, new Date(now().getTime() + 3600000), hash(refresh)]);
  await client.query('INSERT INTO app_refresh_tokens (token_hash, device_id) VALUES ($1,$2)', [hash(refresh), deviceId]);
  return { access_token: access, token_type: 'Bearer', expires_in: 3600, refresh_token: refresh, device_id: deviceId };
}

export async function exchangeAppToken(input: Record<string, unknown>) {
  if (input.grant_type === 'refresh_token') return refreshAppToken(input.refresh_token);
  if (input.grant_type !== 'authorization_code' || !validToken(input.code)
    || typeof input.code_verifier !== 'string' || !/^[A-Za-z0-9._~-]{43,128}$/.test(input.code_verifier)
    || typeof input.device_name !== 'string' || !input.device_name.trim() || input.device_name.trim().length > 100 || input.device_name.includes('\0')) return null;
  const codeHash = hash(input.code);
  const challenge = createHash('sha256').update(input.code_verifier).digest('base64url');
  const deviceName = input.device_name.trim();
  return withTransaction(async client => {
    const pending = (await client.query('SELECT member_id FROM app_login_codes WHERE code_hash = $1', [codeHash])).rows[0];
    if (!pending) return null;
    const member = await client.query('SELECT id FROM members WHERE id = $1 AND removed_at IS NULL FOR SHARE', [pending.member_id]);
    if (!member.rowCount) return null;
    const code = (await client.query('SELECT challenge, expires_at FROM app_login_codes WHERE code_hash = $1 FOR UPDATE', [codeHash])).rows[0];
    if (!code || code.expires_at <= now()) return null;
    limitAppTokens('member', pending.member_id);
    if (!timingSafeEqual(Buffer.from(challenge), Buffer.from(code.challenge))) return null;
    await client.query('DELETE FROM app_login_codes WHERE code_hash = $1', [codeHash]);
    const device = (await client.query('INSERT INTO app_devices (member_id, name, created_at, last_used_at) VALUES ($1,$2,$3,$3) RETURNING id',
      [pending.member_id, deviceName, now()])).rows[0];
    return issuePair(client, device.id);
  });
}

async function refreshAppToken(token: unknown) {
  if (!validToken(token)) return null;
  const tokenHash = hash(token);
  return withTransaction(async client => {
    const pending = (await client.query(`SELECT d.id, d.member_id FROM app_refresh_tokens t
      JOIN app_devices d ON d.id = t.device_id WHERE t.token_hash = $1`, [tokenHash])).rows[0];
    if (!pending) return null;
    limitAppTokens('device', pending.id);
    const member = await client.query('SELECT id FROM members WHERE id = $1 AND removed_at IS NULL FOR SHARE', [pending.member_id]);
    if (!member.rowCount) return null;
    const device = (await client.query('SELECT revoked_at, last_used_at FROM app_devices WHERE id = $1 FOR UPDATE', [pending.id])).rows[0];
    if (device.revoked_at) return null;
    const time = now();
    const refresh = (await client.query('SELECT used_at, first_used_at, successor_hash, grace_issues FROM app_refresh_tokens WHERE token_hash = $1 FOR UPDATE', [tokenHash])).rows[0];
    let rotatingHash = tokenHash;
    let retryAllowed = false;
    // A discarded successor was never presented and must not recover again.
    if (refresh?.used_at && refresh.first_used_at && refresh.grace_issues < MAX_GRACE_ISSUES
      && time.getTime() >= refresh.used_at.getTime()
      && time.getTime() - refresh.used_at.getTime() <= RETRY_MS) {
      const successor = (await client.query(`SELECT token_hash FROM app_refresh_tokens
        WHERE token_hash = $1 AND device_id = $2 AND used_at IS NULL AND first_used_at IS NULL`,
        [refresh.successor_hash, pending.id])).rows[0];
      if (successor) {
        retryAllowed = true;
        rotatingHash = successor.token_hash;
      }
    }
    if (!refresh || (refresh.used_at && !retryAllowed) || lastUse(pending.id, device.last_used_at).getTime() <= time.getTime() - IDLE_MS) {
      await client.query('UPDATE app_devices SET revoked_at = $2 WHERE id = $1', [pending.id, time]);
      return null;
    }
    limitAppTokens('member', pending.member_id);
    await client.query(`UPDATE app_refresh_tokens SET used_at = $2,
      first_used_at = CASE WHEN $3 THEN first_used_at ELSE COALESCE(first_used_at, $2) END
      WHERE token_hash = $1`, [rotatingHash, time, retryAllowed]);
    await recordUse(client, pending.id, device.last_used_at, time);
    await client.query('DELETE FROM app_access_tokens WHERE device_id = $1', [pending.id]);
    const pair = await issuePair(client, pending.id);
    // Keep the original predecessor and rotation time across unused replacements.
    await client.query(`UPDATE app_refresh_tokens SET successor_hash = $2, grace_issues = grace_issues + $3
      WHERE token_hash = $1`, [tokenHash, hash(pair.refresh_token), retryAllowed ? 1 : 0]);
    return pair;
  });
}

export async function revokeAppDevice(request: Request) {
  const deviceId = bearerDevice(request);
  if (!deviceId) return false;
  await db().query('UPDATE app_devices SET revoked_at = $2 WHERE id = $1 AND revoked_at IS NULL', [deviceId, now()]);
  return true;
}

const bearerDevices = new WeakMap<Request, string>();
export function bearerDevice(request: Request): string | undefined { return bearerDevices.get(request); }

export async function bearerIdentity(request: Request): Promise<Identity | null> {
  const authorization = request.headers.get('authorization');
  const token = authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/i)?.[1];
  if (!token) return null;
  const tokenHash = hash(token);
  return withTransaction(async client => {
    const pending = (await client.query('SELECT device_id FROM app_access_tokens WHERE token_hash = $1', [tokenHash])).rows[0];
    if (!pending) return null;
    // Access first-use and refresh rotation must share the device lock.
    await client.query('SELECT id FROM app_devices WHERE id = $1 FOR UPDATE', [pending.device_id]);
    const time = now();
    const row = (await client.query(`SELECT d.id AS device_id, d.last_used_at, t.refresh_hash, m.id AS member_id, m.role, h.id AS household_id, h.name
      FROM app_access_tokens t JOIN app_devices d ON d.id = t.device_id
      JOIN members m ON m.id = d.member_id JOIN households h ON h.id = m.household_id
      WHERE t.token_hash = $1 AND t.expires_at > $2 AND d.revoked_at IS NULL AND m.removed_at IS NULL`,
      [tokenHash, time])).rows[0];
    if (!row || lastUse(row.device_id, row.last_used_at).getTime() <= time.getTime() - IDLE_MS) return null;
    await client.query('UPDATE app_refresh_tokens SET first_used_at = $2 WHERE token_hash = $1 AND first_used_at IS NULL',
      [row.refresh_hash, time]);
    await recordUse(client, row.device_id, row.last_used_at, time);
    bearerDevices.set(request, row.device_id);
    return { member: { id: row.member_id, role: row.role }, household: { id: row.household_id, name: row.name } };
  });
}

import { createHash, randomBytes } from 'node:crypto';
import * as oidc from 'openid-client';
import type { Cookies } from '@sveltejs/kit';
import { config } from './config';
import { db, withTransaction } from './db';
import { now } from './clock';
import { INVITE_COOKIE, inviteHash } from './household';

export const SESSION_COOKIE = 'fytorium_session';
const LOGIN_COOKIE = 'fytorium_login';
const SESSION_SECONDS = 7 * 24 * 60 * 60;

export type Identity = {
  member: { id: string; role: 'owner' | 'family' | 'sitter' };
  household: { id: string; name: string };
};

let oidcConfig: Promise<oidc.Configuration> | undefined;
function provider() {
  const settings = config();
  oidcConfig ??= oidc.discovery(settings.oidcIssuer, settings.oidcClientId, settings.oidcClientSecret,
    oidc.ClientSecretBasic(settings.oidcClientSecret), {
      execute: [oidc.enableNonRepudiationChecks, ...(settings.allowInsecureOidc ? [oidc.allowInsecureRequests] : [])],
    }).catch(error => { oidcConfig = undefined; throw error; });
  return oidcConfig;
}

function randomToken() {
  return randomBytes(32).toString('base64url');
}

function hash(token: string) {
  return createHash('sha256').update(token).digest('hex');
}

function cookieOptions(maxAge: number) {
  return { path: '/', httpOnly: true, sameSite: 'lax' as const, secure: config().oidcRedirectUrl.protocol === 'https:', maxAge };
}

export async function startLogin(cookies: Cookies): Promise<URL> {
  const client = await provider();
  const token = randomToken();
  const state = oidc.randomState();
  const nonce = oidc.randomNonce();
  const verifier = oidc.randomPKCECodeVerifier();
  const challenge = await oidc.calculatePKCECodeChallenge(verifier);
  const time = now();
  await db().query('DELETE FROM login_transactions WHERE expires_at <= $1', [time]);
  await db().query('DELETE FROM sessions WHERE expires_at <= $1', [time]);
  await db().query(
    "INSERT INTO login_transactions (token_hash, state, nonce, verifier, expires_at) VALUES ($1, $2, $3, $4, $5)",
    [hash(token), state, nonce, verifier, new Date(time.getTime() + 600_000)],
  );
  cookies.set(LOGIN_COOKIE, token, cookieOptions(600));
  return oidc.buildAuthorizationUrl(client, {
    redirect_uri: config().oidcRedirectUrl.href, scope: 'openid profile email', response_type: 'code', state, nonce,
    code_challenge: challenge, code_challenge_method: 'S256',
  });
}

export async function finishLogin(url: URL, cookies: Cookies): Promise<boolean> {
  const token = cookies.get(LOGIN_COOKIE);
  const pendingInvite = cookies.get(INVITE_COOKIE);
  cookies.delete(INVITE_COOKIE, { path: '/' });
  cookies.delete(LOGIN_COOKIE, { path: '/' });
  if (!token) throw new Error('Missing login transaction');
  const result = await db().query<{ state: string; nonce: string; verifier: string }>(
    'DELETE FROM login_transactions WHERE token_hash = $1 AND expires_at > $2 RETURNING state, nonce, verifier', [hash(token), now()],
  );
  const transaction = result.rows[0];
  if (!transaction) throw new Error('Expired or already used login transaction');
  // Use the configured redirect origin, not a forwarded Host header.
  const callback = new URL(config().oidcRedirectUrl);
  callback.search = url.search;
  const tokens = await oidc.authorizationCodeGrant(await provider(), callback, {
    pkceCodeVerifier: transaction.verifier, expectedState: transaction.state, expectedNonce: transaction.nonce, idTokenExpected: true,
  });
  const claims = tokens.claims();
  if (!claims?.sub) throw new Error('Missing OIDC subject');
  const session = randomToken();
  const accepted = await withTransaction(async (client) => {
    // Serialize first login across app replicas so only one subject becomes owner.
    await client.query('SELECT pg_advisory_xact_lock(20261011)');
    const existing = await client.query<{ id: string; household_id: string; removed_at: Date | null }>('SELECT id, household_id, removed_at FROM members WHERE issuer = $1 AND subject = $2 FOR UPDATE', [claims.iss, claims.sub]);
    const previousMember = existing.rows[0];
    let memberId = previousMember?.id;
    if (pendingInvite) {
      const invite = (await client.query(`SELECT * FROM household_invites WHERE token_hash = $1
        AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > $2 FOR UPDATE`, [inviteHash(pendingInvite), now()])).rows[0];
      // A repeat holiday can restore a removed member, but cannot move them to another household.
      if (!invite || previousMember && (!previousMember.removed_at || previousMember.household_id !== invite.household_id)) {
        return false;
      }
      if (previousMember) {
        await client.query('UPDATE members SET removed_at = NULL, role = $2, completion_notices_enabled = false WHERE id = $1', [memberId, invite.role]);
        await client.query('DELETE FROM plant_shares WHERE member_id = $1', [memberId]);
      } else {
        const member = await client.query<{ id: string }>('INSERT INTO members (issuer, subject, role, household_id) VALUES ($1,$2,$3,$4) RETURNING id', [claims.iss, claims.sub, invite.role, invite.household_id]);
        memberId = member.rows[0].id;
      }
      if (invite.role === 'sitter') await client.query(`INSERT INTO plant_shares (member_id, household_id, plant_id)
        SELECT $1, ip.household_id, ip.plant_id FROM invite_plants ip JOIN plants p ON p.id = ip.plant_id AND p.household_id = ip.household_id
        WHERE ip.invite_id = $2 AND p.status = 'active'`, [memberId, invite.id]);
      await client.query('UPDATE household_invites SET consumed_at = $2 WHERE id = $1', [invite.id, now()]);
    } else if (previousMember?.removed_at) {
      return false;
    } else if (!memberId) {
      const household = await client.query<{ id: string }>('INSERT INTO households DEFAULT VALUES ON CONFLICT DO NOTHING RETURNING id');
      if (!household.rowCount) { return false; }
      const member = await client.query<{ id: string }>("INSERT INTO members (issuer, subject, role, household_id) VALUES ($1, $2, 'owner', $3) RETURNING id", [claims.iss, claims.sub, household.rows[0].id]);
      memberId = member.rows[0].id;
    }
    const displayName = [claims.name, claims.email].find(value => typeof value === 'string' && value.trim());
    if (displayName) await client.query('UPDATE members SET display_name = $1 WHERE id = $2', [displayName, memberId]);
    const previous = cookies.get(SESSION_COOKIE);
    if (previous) await client.query('DELETE FROM sessions WHERE token_hash = $1', [hash(previous)]);
    await client.query('INSERT INTO sessions (token_hash, member_id, expires_at) VALUES ($1, $2, $3)', [hash(session), memberId, new Date(now().getTime() + SESSION_SECONDS * 1000)]);
    return true;
  });
  if (!accepted) return false;
  cookies.set(SESSION_COOKIE, session, cookieOptions(SESSION_SECONDS));
  return true;
}

export async function identity(cookies: Cookies): Promise<Identity | null> {
  const token = cookies.get(SESSION_COOKIE);
  if (!token) return null;
  const result = await db().query<{ member_id: string; role: Identity['member']['role']; household_id: string; name: string }>(
    `SELECT m.id AS member_id, m.role, h.id AS household_id, h.name
     FROM sessions s JOIN members m ON m.id = s.member_id JOIN households h ON h.id = m.household_id
     WHERE s.token_hash = $1 AND s.expires_at > $2 AND m.removed_at IS NULL`, [hash(token), now()],
  );
  const row = result.rows[0];
  if (!row) { cookies.delete(SESSION_COOKIE, { path: '/' }); return null; }
  return { member: { id: row.member_id, role: row.role }, household: { id: row.household_id, name: row.name } };
}

export async function logout(cookies: Cookies) {
  const token = cookies.get(SESSION_COOKIE);
  if (token) await db().query('DELETE FROM sessions WHERE token_hash = $1', [hash(token)]);
  cookies.delete(SESSION_COOKIE, { path: '/' });
}

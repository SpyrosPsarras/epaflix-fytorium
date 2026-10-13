import { createHash, randomBytes } from 'node:crypto';
import { error } from '@sveltejs/kit';
import type pg from 'pg';
import type { Identity } from './auth';
import { db, withTransaction } from './db';
import { now } from './clock';
import { isUuid } from './plants';
import { requirePermission } from './policy';
export const INVITE_COOKIE = 'fytorium_invite';
export const inviteHash = (token: string) => createHash('sha256').update(token).digest('hex');

async function chosenPlants(client: pg.PoolClient, household: string, input: unknown): Promise<string[]> {
  if (!Array.isArray(input) || input.length > 1000 || !input.every(id => typeof id === 'string' && isUuid(id))) error(422, 'Choose household plants');
  const ids = [...new Set<string>(input)];
  const plants = await client.query("SELECT id FROM plants WHERE household_id = $1 AND id = ANY($2::uuid[]) AND status = 'active' FOR SHARE", [household, ids]);
  if (plants.rowCount !== ids.length) error(422, 'Choose active plants in this household');
  return ids;
}
export async function createInvite(identity: Identity, input: Record<string, unknown>) {
  requirePermission(identity, 'owner');
  if (!['family', 'sitter'].includes(String(input.role))) error(422, 'Choose family or sitter');
  const token = randomBytes(32).toString('base64url');
  return await withTransaction(async (client) => {
    const ids = await chosenPlants(client, identity.household.id, input.plant_ids ?? []);
    if (input.role === 'family' && ids.length) error(422, 'Family members do not need plant shares');
    const result = await client.query(`INSERT INTO household_invites (household_id, token_hash, role, created_at, expires_at)
      VALUES ($1,$2,$3,$4,$5) RETURNING id, role, expires_at`, [identity.household.id, inviteHash(token), input.role, now(), new Date(now().getTime() + 7 * 86400000)]);
    for (const id of ids) await client.query('INSERT INTO invite_plants (invite_id, household_id, plant_id) VALUES ($1,$2,$3)', [result.rows[0].id, identity.household.id, id]);
    return { ...result.rows[0], link: '/invite/' + token };
  });
}
export async function members(identity: Identity) {
  requirePermission(identity, 'owner');
  return (await db().query(`SELECT m.id, m.display_name, m.role,
    COALESCE((SELECT json_agg(plant_id ORDER BY plant_id) FROM plant_shares WHERE member_id = m.id), '[]') AS plant_ids
    FROM members m WHERE household_id = $1 AND removed_at IS NULL ORDER BY role, display_name, id`, [identity.household.id])).rows;
}
export async function invites(identity: Identity) {
  requirePermission(identity, 'owner');
  return (await db().query(`SELECT i.id, i.role, i.created_at, i.expires_at,
    COALESCE((SELECT json_agg(plant_id ORDER BY plant_id) FROM invite_plants WHERE invite_id = i.id), '[]') AS plant_ids
    FROM household_invites i WHERE household_id = $1 AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > $2 ORDER BY created_at, id`, [identity.household.id, now()])).rows;
}
export async function revokeInvite(identity: Identity, id: string) {
  requirePermission(identity, 'owner');
  if (!isUuid(id)) error(404, 'Invite not found');
  const result = await db().query('UPDATE household_invites SET revoked_at = $3 WHERE id = $1 AND household_id = $2 AND consumed_at IS NULL AND revoked_at IS NULL RETURNING id', [id, identity.household.id, now()]);
  if (!result.rowCount) error(404, 'Invite not found');
}
export async function changeShares(identity: Identity, memberId: string, input: Record<string, unknown>) {
  requirePermission(identity, 'owner');
  if (!isUuid(memberId)) error(404, 'Member not found');
  await withTransaction(async (client) => {
    const member = await client.query("SELECT id FROM members WHERE id = $1 AND household_id = $2 AND role = 'sitter' AND removed_at IS NULL FOR UPDATE", [memberId, identity.household.id]);
    if (!member.rowCount) error(404, 'Sitter not found');
    const ids = await chosenPlants(client, identity.household.id, input.plant_ids);
    await client.query('DELETE FROM plant_shares WHERE member_id = $1', [memberId]);
    for (const id of ids) await client.query('INSERT INTO plant_shares (member_id, household_id, plant_id) VALUES ($1,$2,$3)', [memberId, identity.household.id, id]);
  });
}
export async function removeMember(identity: Identity, memberId: string) {
  requirePermission(identity, 'owner');
  if (!isUuid(memberId)) error(404, 'Member not found');
  if (memberId === identity.member.id) error(409, 'You cannot remove yourself');
  await withTransaction(async (client) => {
    await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [identity.household.id]);
    const target = await client.query('SELECT role FROM members WHERE id = $1 AND household_id = $2 AND removed_at IS NULL FOR UPDATE', [memberId, identity.household.id]);
    if (!target.rowCount) error(404, 'Member not found');
    if (target.rows[0].role === 'owner') {
      const owners = await client.query("SELECT id FROM members WHERE household_id = $1 AND role = 'owner' AND removed_at IS NULL", [identity.household.id]);
      if (owners.rowCount === 1) error(409, 'The last owner cannot be removed');
    }
    // Keep the member row and care-event name snapshots for historical attribution.
    await client.query('UPDATE members SET removed_at = $2 WHERE id = $1', [memberId, now()]);
    await client.query('DELETE FROM sessions WHERE member_id = $1', [memberId]);
    await client.query('UPDATE app_devices SET revoked_at = $2 WHERE member_id = $1 AND revoked_at IS NULL', [memberId, now()]);
    await client.query('DELETE FROM app_login_codes WHERE member_id = $1', [memberId]);
    await client.query('DELETE FROM push_subscriptions WHERE member_id = $1', [memberId]);
    await client.query('DELETE FROM plant_shares WHERE member_id = $1', [memberId]);
  });
}

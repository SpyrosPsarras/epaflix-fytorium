import { error, json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { db } from '$lib/server/db';

// The member's own name and the owner's name, for the app's More header and a sitter's "for <owner>".
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  const names = (await db().query<{ display_name: string; owner_name: string | null }>(
    `SELECT m.display_name, (SELECT o.display_name FROM members o WHERE o.household_id = m.household_id AND o.role = 'owner' AND o.removed_at IS NULL
       ORDER BY o.display_name, o.id LIMIT 1) AS owner_name FROM members m WHERE m.id = $1`, [locals.identity.member.id])).rows[0];
  return json({ ...locals.identity, member: { ...locals.identity.member, display_name: names?.display_name ?? null },
    household: { ...locals.identity.household, owner_name: names?.owner_name ?? null } });
};

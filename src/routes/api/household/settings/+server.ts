import { error, json } from '@sveltejs/kit';
import { householdSettings, readSettings } from '$lib/server/care-settings';
import { checkOrigin } from '$lib/server/sites';
import { db } from '$lib/server/db';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await householdSettings(locals.identity.household.id));
};
export const PUT: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const input = await readSettings(request);
  const result = await db().query('UPDATE households SET misting_enabled = $1 WHERE id = $2 RETURNING misting_enabled', [input.misting_enabled, locals.identity.household.id]);
  return json(result.rows[0]);
};

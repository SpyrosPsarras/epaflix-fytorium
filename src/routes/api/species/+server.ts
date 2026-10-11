import { error, json } from '@sveltejs/kit';
import { db } from '$lib/server/db';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json((await db().query('SELECT * FROM species ORDER BY scientific_name, gbif_key')).rows);
};

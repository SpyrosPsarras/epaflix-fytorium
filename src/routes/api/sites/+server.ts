import { error, json } from '@sveltejs/kit';
import { db } from '$lib/server/db';
import { checkOrigin, readSite } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  const result = await db().query(
    `SELECT s.*, count(p.id)::integer AS plant_count
     FROM sites s LEFT JOIN plants p ON p.site_id = s.id AND p.household_id = s.household_id AND p.status = 'active'
     WHERE s.household_id = $1 GROUP BY s.id ORDER BY s.name, s.id`,
    [locals.identity.household.id],
  );
  return json(result.rows);
};

export const POST: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const site = await readSite(request);
  if (site instanceof Response) return site;
  const result = await db().query(
    `INSERT INTO sites (household_id, name, location, light, grow_light, warm_min_c, warm_max_c, cold_min_c, cold_max_c, humidity, draft)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) RETURNING *`,
    [locals.identity.household.id, site.name, site.location, site.light, site.grow_light, site.warm_min_c, site.warm_max_c, site.cold_min_c, site.cold_max_c, site.humidity, site.draft],
  );
  return json(result.rows[0], { status: 201 });
};

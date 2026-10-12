import { error, json } from '@sveltejs/kit';
import { db, withTransaction } from '$lib/server/db';
import { checkOrigin, readSite } from '$lib/server/sites';
import type { RequestHandler } from './$types';

function isSiteId(id: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id);
}

export const DELETE: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  if (!isSiteId(params.id)) error(404, 'Site not found');
  checkOrigin(request);
  const household = locals.identity.household.id;
  let result;
  try {
    result = await withTransaction(async (client) => {
      await client.query("UPDATE plants SET site_id = NULL WHERE site_id = $1 AND household_id = $2 AND status = 'graveyard'", [params.id, household]);
      // Active plants retain the RESTRICT FK, including concurrent additions and restores.
      return client.query('DELETE FROM sites WHERE id = $1 AND household_id = $2', [params.id, household]);
    });
  } catch (cause) {
    if (!(cause instanceof Error && 'code' in cause && cause.code === '23503')) throw cause;
    const count = await db().query<{ count: number }>(
      "SELECT count(*)::integer AS count FROM plants WHERE site_id = $1 AND household_id = $2 AND status = 'active'",
      [params.id, locals.identity.household.id],
    );
    const plants = count.rows[0].count;
    return json({ message: `Move or remove the ${plants} ${plants === 1 ? 'plant' : 'plants'} on this site first.` }, { status: 409 });
  }
  if (!result.rowCount) error(404, 'Site not found');
  return new Response(null, { status: 204 });
};

export const PUT: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  if (!isSiteId(params.id)) error(404, 'Site not found');
  checkOrigin(request);
  const site = await readSite(request);
  if (site instanceof Response) return site;
  const result = await db().query(
    `UPDATE sites SET name = $3, location = $4, light = $5, grow_light = $6,
     warm_min_c = $7, warm_max_c = $8, cold_min_c = $9, cold_max_c = $10, humidity = $11, draft = $12, imported_conditions_incomplete = false
     WHERE id = $1 AND household_id = $2 RETURNING *`,
    [params.id, locals.identity.household.id, site.name, site.location, site.light, site.grow_light, site.warm_min_c, site.warm_max_c, site.cold_min_c, site.cold_max_c, site.humidity, site.draft],
  );
  if (!result.rowCount) error(404, 'Site not found');
  return json(result.rows[0]);
};

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  if (!isSiteId(params.id)) error(404, 'Site not found');
  const result = await db().query('SELECT * FROM sites WHERE id = $1 AND household_id = $2', [params.id, locals.identity.household.id]);
  if (!result.rowCount) error(404, 'Site not found');
  return json(result.rows[0]);
};

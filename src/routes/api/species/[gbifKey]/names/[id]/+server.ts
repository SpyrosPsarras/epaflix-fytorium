import { error } from '@sveltejs/kit';
import { db } from '$lib/server/db';
import { getSpecies } from '$lib/server/names';
import { checkOrigin } from '$lib/server/sites';
import { isUuid } from '$lib/server/plants';
import type { RequestHandler } from './$types';
export const DELETE: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  if (locals.identity.member.role !== 'owner') error(403, 'Only the owner can edit names');
  checkOrigin(request);
  await getSpecies(params.gbifKey);
  if (!isUuid(params.id)) error(404, 'Owner name not found');
  const result = await db().query("DELETE FROM species_names WHERE gbif_key = $1 AND id = $2 AND source = 'owner'", [params.gbifKey, params.id]);
  if (!result.rowCount) error(404, 'Owner name not found');
  return new Response(null, { status: 204 });
};

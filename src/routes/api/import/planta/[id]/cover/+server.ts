import { error } from '@sveltejs/kit';
import { isUuid } from '$lib/server/plants';
import { requireOwner } from '$lib/server/planta/import';
import { coverThumbnail } from '$lib/server/planta/cover';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  const identity = requireOwner(locals.identity);
  if (!isUuid(params.id)) error(404, 'Planta cover not found');
  const thumbnail = await coverThumbnail(params.id, identity.household.id);
  return new Response(new Uint8Array(thumbnail), { headers: { 'content-type': 'image/jpeg', 'cache-control': 'private, max-age=3600' } });
};

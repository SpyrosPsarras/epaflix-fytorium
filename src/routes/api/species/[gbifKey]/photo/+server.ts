import { error } from '@sveltejs/kit';
import { speciesPhoto } from '$lib/server/species-photos';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  const photo = await speciesPhoto(params.gbifKey);
  if (!photo.thumbnail) error(404, 'No reference photo');
  return new Response(new Uint8Array(photo.thumbnail), { headers: { 'content-type': 'image/jpeg', 'cache-control': 'private, max-age=86400' } });
};

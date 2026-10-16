import { error, json } from '@sveltejs/kit';
import { vernacularName } from '$lib/names';
import { speciesPhoto } from '$lib/server/species-photos';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  const { thumbnail, creator, license, source_url, common_names } = await speciesPhoto(params.gbifKey);
  return json({ photo: thumbnail ? { creator, license, source_url } : null, common_name: vernacularName(common_names) }, { headers: { 'cache-control': thumbnail ? 'private, max-age=86400' : 'no-store' } });
};

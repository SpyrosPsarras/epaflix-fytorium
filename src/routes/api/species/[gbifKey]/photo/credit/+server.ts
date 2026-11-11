import { error, json } from '@sveltejs/kit';
import { speciesCommonNames } from '$lib/server/species';
import { speciesPhoto } from '$lib/server/species-photos';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  const { thumbnail, creator, license, source_url } = await speciesPhoto(params.gbifKey);
  const key = Number(params.gbifKey);
  return json({ photo: thumbnail ? { creator, license, source_url } : null, common_name: (await speciesCommonNames([key], locals.identity.language)).get(key) ?? null }, { headers: { 'cache-control': 'no-store' } });
};

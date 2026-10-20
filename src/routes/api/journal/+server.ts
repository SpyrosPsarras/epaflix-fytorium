import { error, json } from '@sveltejs/kit';
import { listPhotos } from '$lib/server/photos';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await listPhotos(locals.identity.household.id, undefined, locals.identity.language));
};

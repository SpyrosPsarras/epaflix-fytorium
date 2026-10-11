import { error, json } from '@sveltejs/kit';
import { addPhoto, listPhotos } from '$lib/server/photos';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await listPhotos(locals.identity.household.id, params.id));
};
export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await addPhoto(locals.identity, params.id, request), { status: 201 });
};

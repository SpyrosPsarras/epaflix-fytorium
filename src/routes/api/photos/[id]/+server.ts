import { error } from '@sveltejs/kit';
import { deletePhoto } from '$lib/server/photos';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const DELETE: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await deletePhoto(params.id, locals.identity.household.id, locals.identity.language);
  return new Response(null, { status: 204 });
};

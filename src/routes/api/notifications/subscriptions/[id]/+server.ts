import { error } from '@sveltejs/kit';
import { unsubscribe } from '$lib/server/notifications/subscriptions';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const DELETE: RequestHandler = async ({ locals, request, params }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await unsubscribe(locals.identity, params.id);
  return new Response(null, { status: 204 });
};

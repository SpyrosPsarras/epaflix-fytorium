import { error, json } from '@sveltejs/kit';
import { switchSubscription, unsubscribe } from '$lib/server/notifications/subscriptions';
import { readJsonObject } from '$lib/server/json';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const PATCH: RequestHandler = async ({ locals, request, params }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await switchSubscription(locals.identity, params.id, (await readJsonObject(request)).enabled));
};
export const DELETE: RequestHandler = async ({ locals, request, params }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await unsubscribe(locals.identity, params.id);
  return new Response(null, { status: 204 });
};

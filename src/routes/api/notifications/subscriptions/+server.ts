import { error, json } from '@sveltejs/kit';
import { subscribe, subscriptions } from '$lib/server/notifications/subscriptions';
import { readJsonObject } from '$lib/server/json';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await subscriptions(locals.identity));
};
export const POST: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return json(await subscribe(locals.identity, await readJsonObject(request), request.headers.get('user-agent') ?? ''), { status: 201 });
};

import { error, json } from '@sveltejs/kit';
import { registerFcm, subscribe, subscriptions } from '$lib/server/notifications/subscriptions';
import { bearerDevice } from '$lib/server/app-auth';
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
  const input = await readJsonObject(request);
  return json(input.transport === 'fcm' ? await registerFcm(locals.identity, bearerDevice(request), input)
    : await subscribe(locals.identity, input, request.headers.get('user-agent') ?? ''), { status: 201 });
};

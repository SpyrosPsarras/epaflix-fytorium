import { error, json } from '@sveltejs/kit';
import { sendToMember } from '$lib/server/notifications/delivery';
import { config } from '$lib/server/config';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  if (!config().vapidPublicKey) error(503, 'Notifications are not configured');
  return json(await sendToMember(locals.identity.member.id, { title: 'Fytorium', body: 'Notifications work on this device.', tag: 'fytorium-test', url: '/' }));
};

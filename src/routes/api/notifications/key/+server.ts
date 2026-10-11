import { error, json } from '@sveltejs/kit';
import { config } from '$lib/server/config';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  if (!config().vapidPublicKey) error(503, 'Notifications are not configured');
  return json({ publicKey: config().vapidPublicKey });
};

import { error, json } from '@sveltejs/kit';
import { homeFeed } from '$lib/server/feed';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const { cards, settings } = await homeFeed(locals.identity);
  return json({ cards, settings });
};

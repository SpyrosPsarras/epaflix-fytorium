import { error, json } from '@sveltejs/kit';
import { retryPlantbookMatch } from '$lib/server/care/catalogue';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await retryPlantbookMatch(params.gbifKey, locals.identity.household.id);
  return json({ queued: true }, { status: 202 });
};

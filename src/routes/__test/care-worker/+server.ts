import { error, json } from '@sveltejs/kit';
import { config } from '$lib/server/config';
import { runCareWorkerOnce } from '$lib/server/care/worker';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, request }) => {
  if (!config().testClockEnabled) error(404, 'Not found');
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await runCareWorkerOnce();
  return json({ processed: true });
};

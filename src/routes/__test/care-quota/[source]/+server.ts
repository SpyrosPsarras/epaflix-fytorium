import { error, json } from '@sveltejs/kit';
import { config } from '$lib/server/config';
import { db } from '$lib/server/db';
import { careSources } from '$lib/server/care/registry';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';

export const DELETE: RequestHandler = async ({ locals, request, params }) => {
  if (!config().testClockEnabled) error(404, 'Not found');
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  if (!careSources().some(({ name }) => name === params.source)) error(404, 'Unknown care source');
  await db().query('DELETE FROM care_source_requests WHERE source = $1', [params.source]);
  return json({ reset: true });
};

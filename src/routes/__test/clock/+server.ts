import { error, isHttpError, json } from '@sveltejs/kit';
import { readJson } from '$lib/server/json';
import { config } from '$lib/server/config';
import { now, controlClock } from '$lib/server/clock';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = () => {
  if (!config().testClockEnabled) error(404, 'Not found');
  return json({ now: now().toISOString() });
};

export const POST: RequestHandler = async ({ request }) => {
  if (!config().testClockEnabled) error(404, 'Not found');
  const origin = request.headers.get('origin');
  if (origin && origin !== config().origin.origin) error(403, 'Wrong origin');
  try {
    return json({ now: controlClock(await readJson(request, { requireContentType: false })).toISOString() });
  } catch (cause) {
    if (isHttpError(cause) && cause.status === 413) throw cause;
    error(400, 'Expected set with a valid at date, advance with finite milliseconds, or reset');
  }
};

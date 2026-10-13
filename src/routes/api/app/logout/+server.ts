import { json } from '@sveltejs/kit';
import { revokeAppDevice } from '$lib/server/app-auth';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async ({ request }) => {
  if (!await revokeAppDevice(request)) return json({ error: 'invalid_token' }, { status: 401 });
  return new Response(null, { status: 204 });
};

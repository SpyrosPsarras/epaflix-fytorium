import { error, json } from '@sveltejs/kit';
import { setCareOverride, removeCareOverride } from '$lib/server/care/catalogue';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const PUT: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  let body;
  try { body = await request.json(); } catch { error(400, 'Send valid JSON'); }
  if (!body || typeof body !== 'object' || !('value' in body)) error(422, 'Send a value');
  return json(await setCareOverride(params.gbifKey, params.field, body.value, locals.identity.household.id));
};
export const DELETE: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  await removeCareOverride(params.gbifKey, params.field, locals.identity.household.id);
  return new Response(null, { status: 204 });
};

import { json } from '@sveltejs/kit';
import { changeShares, removeMember } from '$lib/server/household';
import { requirePermission } from '$lib/server/policy';
import { readJsonObject } from '$lib/server/json';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const PUT: RequestHandler = async ({ locals, params, request }) => {
  const identity = requirePermission(locals.identity, 'owner');
  checkOrigin(request);
  await changeShares(identity, params.id, await readJsonObject(request));
  return json({ saved: true });
};
export const DELETE: RequestHandler = async ({ locals, params, request }) => {
  const identity = requirePermission(locals.identity, 'owner');
  checkOrigin(request);
  await removeMember(identity, params.id);
  return new Response(null, { status: 204 });
};

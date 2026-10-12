import { json } from '@sveltejs/kit';
import { createInvite, invites } from '$lib/server/household';
import { requirePermission } from '$lib/server/policy';
import { readJsonObject } from '$lib/server/json';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => json(await invites(requirePermission(locals.identity, 'owner')));
export const POST: RequestHandler = async ({ locals, request }) => {
  const identity = requirePermission(locals.identity, 'owner');
  checkOrigin(request);
  return json(await createInvite(identity, await readJsonObject(request)), { status: 201 });
};

import { revokeInvite } from '$lib/server/household';
import { requirePermission } from '$lib/server/policy';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const DELETE: RequestHandler = async ({ locals, params, request }) => {
  const identity = requirePermission(locals.identity, 'owner');
  checkOrigin(request);
  await revokeInvite(identity, params.id);
  return new Response(null, { status: 204 });
};

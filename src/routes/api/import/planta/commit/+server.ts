import { json } from '@sveltejs/kit';
import { requireOwner } from '$lib/server/planta/import';
import { commitImport } from '$lib/server/planta/commit';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, request }) => {
  const identity = requireOwner(locals.identity);
  checkOrigin(request);
  return json(await commitImport(identity));
};

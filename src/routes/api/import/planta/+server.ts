import { error, json } from '@sveltejs/kit';
import { requireOwner, readImportInput, stageImport, staging } from '$lib/server/planta/import';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => json(await staging(requireOwner(locals.identity).household.id));
export const POST: RequestHandler = async ({ locals, request }) => {
  const identity = requireOwner(locals.identity);
  checkOrigin(request);
  const input = await readImportInput(request);
  if (typeof input.code !== 'string' || !input.code.trim() || input.code.length > 1000) error(422, 'Enter the one-time app code.');
  return json(await stageImport(input.code.trim(), identity.household.id));
};

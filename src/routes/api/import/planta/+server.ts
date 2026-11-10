import { error, json } from '@sveltejs/kit';
import { requireOwner, stageImport, staging } from '$lib/server/planta/import';
import { readJsonObject } from '$lib/server/json';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => {
  const identity = requireOwner(locals.identity);
  return json(await staging(identity.household.id, identity.language));
};
export const POST: RequestHandler = async ({ locals, request }) => {
  const identity = requireOwner(locals.identity);
  checkOrigin(request);
  const input = await readJsonObject(request);
  if (typeof input.code !== 'string' || !input.code.trim() || input.code.length > 1000) error(422, 'Enter the one-time app code.');
  return json(await stageImport(input.code.trim(), identity.household.id, identity.language));
};

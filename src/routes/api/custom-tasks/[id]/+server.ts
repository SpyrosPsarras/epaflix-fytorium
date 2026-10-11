import { error, json } from '@sveltejs/kit';
import { getCustomTask, editCustomTask, deleteCustomTask } from '$lib/server/custom-tasks';
import { readTaskInput } from '$lib/server/tasks';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(await getCustomTask(locals.identity.household.id, params.id));
};
export const PUT: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return editCustomTask(locals.identity.household.id, params.id, await readTaskInput(request));
};
export const DELETE: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  return deleteCustomTask(locals.identity.household.id, params.id);
};

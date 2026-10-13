import { json } from '@sveltejs/kit';
import { requirePermission } from '$lib/server/policy';
import { listedTaskContext } from '$lib/server/tasks';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals }) => {
  const identity = requirePermission(locals.identity, 'tasks');
  const batch = await listedTaskContext(identity, { view: 'today', forFeed: true });
  return json({ count: batch.plants.length });
};

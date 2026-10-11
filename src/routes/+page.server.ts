import { homeFeed } from '$lib/server/feed';
import { error } from '@sveltejs/kit';
import { listedTaskContext } from '$lib/server/tasks';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch }) => {
  if (!locals.identity) return { identity: null, plantCount: 0 };
  const response = await fetch('/api/plants?count=true');
  if (!response.ok) error(response.status, 'Could not load the plant count');
  const { count } = await response.json() as { count: number };
  const batch = await listedTaskContext(locals.identity, 'today', true);
  const [feed, today] = await Promise.all([homeFeed(locals.identity, batch), listedTaskContext(locals.identity, 'today', false, batch)]);
  return { identity: locals.identity, plantCount: count, feed, tasks: today.tasks };
};

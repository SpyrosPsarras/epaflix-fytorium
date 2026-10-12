import { sitterView } from '$lib/server/policy';
import { homeFeed } from '$lib/server/feed';
import { error } from '@sveltejs/kit';
import { listedTaskContext } from '$lib/server/tasks';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch }) => {
  if (!locals.identity) return { identity: null, plantCount: 0 };
  const batch = await listedTaskContext(locals.identity, { view: 'today', forFeed: true });
  const [feed, today] = await Promise.all([homeFeed(locals.identity, batch), listedTaskContext(locals.identity, { view: 'today', batch })]);
  if (locals.identity.member.role === 'sitter') return sitterView({ identity: locals.identity, plantCount: batch.plants.length, feed, tasks: today.tasks }, 'home');
  const response = await fetch('/api/plants?count=true');
  if (!response.ok) error(response.status, 'Could not load the plant count');
  const { count } = await response.json() as { count: number };
  return { identity: locals.identity, plantCount: count, feed, tasks: today.tasks };
};

import { requirePermission, sitterProjection, sitterView } from '$lib/server/policy';
import { homeFeed } from '$lib/server/feed';
import { error } from '@sveltejs/kit';
import { listedTaskContext } from '$lib/server/tasks';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch, route }) => {
  const identity = requirePermission(locals.identity, 'tasks');
  const batch = await listedTaskContext(identity, { view: 'today', forFeed: true });
  const [feed, today] = await Promise.all([homeFeed(identity, batch), listedTaskContext(identity, { view: 'today', batch })]);
  if (identity.member.role === 'sitter') {
    const projection = sitterProjection(route.id);
    if (projection !== 'home') error(500, 'Expected the home projection');
    return sitterView({ identity, plantCount: batch.plants.length, feed, tasks: today.tasks }, projection);
  }
  const response = await fetch('/api/plants?count=true');
  if (!response.ok) error(response.status, 'Could not load the plant count');
  const { count } = await response.json() as { count: number };
  return { identity, plantCount: count, feed, tasks: today.tasks };
};

import { error } from '@sveltejs/kit';
import { sitterProjection, sitterView } from '$lib/server/policy';
import type { Plant } from '$lib/plants';
import type { LayoutServerLoad } from './$types';
export const load: LayoutServerLoad = async ({ fetch, params, locals, route }) => {
  const response = await fetch('/api/plants/' + params.id);
  if (!response.ok) error(response.status, 'Could not load the plant');
  const plant = await response.json();
  return { plant: (locals.identity?.member.role === 'sitter' ? sitterView(plant, sitterProjection(route.id)) : plant) as Plant };
};

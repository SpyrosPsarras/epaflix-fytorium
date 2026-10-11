import { error } from '@sveltejs/kit';
import type { Plant } from '$lib/plants';
import type { LayoutServerLoad } from './$types';
export const load: LayoutServerLoad = async ({ fetch, params }) => {
  const response = await fetch('/api/plants/' + params.id);
  if (!response.ok) error(response.status, 'Could not load the plant');
  return { plant: await response.json() as Plant };
};

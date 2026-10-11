import { error } from '@sveltejs/kit';
import type { PhotoUpdate } from '$lib/photos';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ fetch, params, parent }) => {
  await parent();
  const response = await fetch('/api/plants/' + params.id + '/photos');
  if (!response.ok) error(response.status, 'Could not load photo updates');
  return { updates: await response.json() as PhotoUpdate[] };
};

import { error } from '@sveltejs/kit';
import type { Plant } from '$lib/plants';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ fetch }) => {
  const response = await fetch('/api/plants?status=graveyard');
  if (!response.ok) error(response.status, 'Could not load the graveyard');
  return { plants: await response.json() as Plant[] };
};

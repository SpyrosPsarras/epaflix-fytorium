import { error } from '@sveltejs/kit';
import type { Site } from '$lib/sites';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ fetch, params }) => {
  const response = await fetch('/api/sites/' + params.id);
  if (!response.ok) error(response.status, 'Could not load the site');
  return { site: await response.json() as Site };
};

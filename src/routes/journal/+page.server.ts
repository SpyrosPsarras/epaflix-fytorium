import { error, redirect } from '@sveltejs/kit';
import type { PhotoUpdate } from '$lib/photos';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ fetch, locals }) => {
  if (!locals.identity) redirect(303, '/auth/login');
  const response = await fetch('/api/journal');
  if (!response.ok) error(response.status, 'Could not load the journal');
  return { updates: await response.json() as PhotoUpdate[] };
};

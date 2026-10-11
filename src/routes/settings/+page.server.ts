import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch }) => {
  if (!locals.identity) error(401, 'Login required');
  const response = await fetch('/api/household/settings');
  if (!response.ok) error(response.status, 'Could not load household settings');
  return { settings: await response.json() as { misting_enabled: boolean } };
};

import { error } from '@sveltejs/kit';
import type { ListedSite } from '$lib/sites';
import { identificationQuota } from '$lib/server/identification';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ fetch }) => {
  const response = await fetch('/api/sites');
  if (!response.ok) error(response.status, 'Could not load sites');
  return { sites: await response.json() as ListedSite[], ...await identificationQuota() };
};

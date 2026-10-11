import { error } from '@sveltejs/kit';
import type { PlantCareSettings } from '$lib/server/care-settings';
import type { ListedSite } from '$lib/sites';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ fetch, params }) => {
  const [response, settings] = await Promise.all([fetch('/api/sites'), fetch('/api/plants/' + params.id + '/care-settings')]);
  if (!response.ok) error(response.status, 'Could not load sites');
  if (!settings.ok) error(settings.status, 'Could not load care settings');
  return { sites: await response.json() as ListedSite[], careSettings: await settings.json() as PlantCareSettings };
};

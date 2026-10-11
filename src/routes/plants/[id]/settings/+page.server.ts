import { error } from '@sveltejs/kit';
import type { PlantCareSettings } from '$lib/server/care-settings';
import type { ListedSite } from '$lib/sites';
import type { CustomTask } from '$lib/custom-tasks';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ fetch, params }) => {
  const [response, settings, custom] = await Promise.all([fetch('/api/sites'), fetch('/api/plants/' + params.id + '/care-settings'), fetch('/api/custom-tasks?plant_id=' + params.id)]);
  if (!custom.ok) error(custom.status, 'Could not load custom tasks');
  if (!response.ok) error(response.status, 'Could not load sites');
  if (!settings.ok) error(settings.status, 'Could not load care settings');
  return { customTasks: await custom.json() as CustomTask[], sites: await response.json() as ListedSite[], careSettings: await settings.json() as PlantCareSettings };
};

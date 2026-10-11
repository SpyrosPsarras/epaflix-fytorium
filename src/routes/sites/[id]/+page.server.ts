import { error } from '@sveltejs/kit';
import type { Site } from '$lib/sites';
import type { Plant } from '$lib/plants';
import type { CustomTask } from '$lib/custom-tasks';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ fetch, params }) => {
  const response = await fetch('/api/sites/' + params.id);
  if (!response.ok) error(response.status, 'Could not load the site');
  const plants = await fetch('/api/plants?site_id=' + encodeURIComponent(params.id));
  if (!plants.ok) error(plants.status, 'Could not load site plants');
  const custom = await fetch('/api/custom-tasks?site_id=' + params.id);
  if (!custom.ok) error(custom.status, 'Could not load custom tasks');
  return { customTasks: await custom.json() as CustomTask[], site: await response.json() as Site, plants: await plants.json() as Plant[] };
};

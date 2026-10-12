import { error } from '@sveltejs/kit';
import type { Site } from '$lib/sites';
import type { Plant } from '$lib/plants';
import type { CustomTask } from '$lib/custom-tasks';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ fetch, params }) => {
  const id = encodeURIComponent(params.id);
  const [response, plants, custom] = await Promise.all([
    fetch('/api/sites/' + id),
    fetch('/api/plants?site_id=' + id),
    fetch('/api/custom-tasks?site_id=' + id),
  ]);
  if (!response.ok) error(response.status, 'Could not load the site');
  if (!plants.ok) error(plants.status, 'Could not load site plants');
  if (!custom.ok) error(custom.status, 'Could not load custom tasks');
  return { customTasks: await custom.json() as CustomTask[], site: await response.json() as Site, plants: await plants.json() as Plant[] };
};

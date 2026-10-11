import { weatherReport } from '$lib/server/weather/cache';
import { error } from '@sveltejs/kit';
import { photoToday } from '$lib/server/photos';
import type { PhotoUpdate } from '$lib/photos';
import type { PlantSpeciesChange } from '$lib/plants';
import type { ListedSite } from '$lib/sites';
import type { CareEvent } from '$lib/tasks';
import type { Task, TaskType } from '$lib/schedule/engine';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ fetch, params, parent, locals }) => {
  const { plant } = await parent();
  let sites: ListedSite[] = [];
  if (plant.status === 'graveyard') {
    const response = await fetch('/api/sites');
    if (!response.ok) error(response.status, 'Could not load sites for restore');
    sites = await response.json();
  }
  const [tasks, history, photos, changes] = await Promise.all([
    fetch('/api/plants/' + params.id + '/tasks'), fetch('/api/plants/' + params.id + '/history'), fetch('/api/plants/' + params.id + '/photos'), fetch('/api/plants/' + params.id + '/species-changes'),
  ]);
  if (!tasks.ok) error(tasks.status, 'Could not load the schedule');
  if (!history.ok) error(history.status, 'Could not load care history');
  if (!photos.ok) error(photos.status, 'Could not load photo updates');
  if (!changes.ok) error(changes.status, 'Could not load species history');
  return { weather: await weatherReport(locals.identity!.household.id, params.id), sites, speciesChanges: await changes.json() as PlantSpeciesChange[], tasks: await tasks.json() as Record<TaskType, Task>, history: await history.json() as CareEvent[], updates: await photos.json() as PhotoUpdate[], photoToday: photoToday() };
};

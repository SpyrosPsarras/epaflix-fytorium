import { error } from '@sveltejs/kit';
import { photoToday } from '$lib/server/photos';
import type { PhotoUpdate } from '$lib/photos';
import type { CareEvent } from '$lib/tasks';
import type { Task, TaskType } from '$lib/schedule/engine';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ fetch, params, parent }) => {
  await parent();
  const [tasks, history, photos] = await Promise.all([
    fetch('/api/plants/' + params.id + '/tasks'), fetch('/api/plants/' + params.id + '/history'), fetch('/api/plants/' + params.id + '/photos'),
  ]);
  if (!tasks.ok) error(tasks.status, 'Could not load the schedule');
  if (!history.ok) error(history.status, 'Could not load care history');
  if (!photos.ok) error(photos.status, 'Could not load photo updates');
  return { tasks: await tasks.json() as Record<TaskType, Task>, history: await history.json() as CareEvent[], updates: await photos.json() as PhotoUpdate[], photoToday: photoToday() };
};

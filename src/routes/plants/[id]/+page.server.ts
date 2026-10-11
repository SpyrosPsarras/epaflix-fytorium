import { error } from '@sveltejs/kit';
import type { CareEvent } from '$lib/tasks';
import type { Task, TaskType } from '$lib/schedule/engine';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ fetch, params, parent }) => {
  await parent();
  const [tasks, history] = await Promise.all([
    fetch('/api/plants/' + params.id + '/tasks'), fetch('/api/plants/' + params.id + '/history'),
  ]);
  if (!tasks.ok) error(tasks.status, 'Could not load the schedule');
  if (!history.ok) error(history.status, 'Could not load care history');
  return { tasks: await tasks.json() as Record<TaskType, Task>, history: await history.json() as CareEvent[] };
};

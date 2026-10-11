import { error } from '@sveltejs/kit';
import type { TaskList } from '$lib/tasks';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch }) => {
  if (!locals.identity) return { identity: null, plantCount: 0 };
  const response = await fetch('/api/plants?count=true');
  if (!response.ok) error(response.status, 'Could not load the plant count');
  const { count } = await response.json() as { count: number };
  const tasks = await fetch('/api/tasks?view=today');
  if (!tasks.ok) error(tasks.status, 'Could not load today’s tasks');
  return { identity: locals.identity, plantCount: count, tasks: await tasks.json() as TaskList };
};

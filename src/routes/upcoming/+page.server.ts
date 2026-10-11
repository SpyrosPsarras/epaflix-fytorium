import { error, redirect } from '@sveltejs/kit';
import type { TaskList } from '$lib/tasks';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch }) => {
  if (!locals.identity) redirect(303, '/auth/login');
  const response = await fetch('/api/tasks?view=upcoming');
  if (!response.ok) error(response.status, 'Could not load upcoming tasks');
  return { tasks: await response.json() as TaskList };
};

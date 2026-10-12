import { error, redirect } from '@sveltejs/kit';
import { sitterProjection, sitterView } from '$lib/server/policy';
import type { TaskList } from '$lib/tasks';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch }) => {
  if (!locals.identity) redirect(303, '/auth/login');
  const response = await fetch('/api/tasks?view=upcoming');
  if (!response.ok) error(response.status, 'Could not load upcoming tasks');
  const tasks = await response.json() as TaskList;
  return { identity: locals.identity, tasks: locals.identity.member.role === 'sitter' ? sitterView(tasks, sitterProjection('/upcoming')) : tasks };
};

import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch }) => {
  if (!locals.identity) return { identity: null, plantCount: 0 };
  const response = await fetch('/api/plants?count=true');
  if (!response.ok) error(response.status, 'Could not load the plant count');
  const { count } = await response.json() as { count: number };
  return { identity: locals.identity, plantCount: count };
};

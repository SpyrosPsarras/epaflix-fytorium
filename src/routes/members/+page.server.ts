import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
import type { Plant } from '$lib/plants';
type Member = { id: string; display_name: string; role: 'owner' | 'family' | 'sitter'; plant_ids: string[] };
type Invite = { id: string; role: string; expires_at: string; plant_ids: string[] };
export const load: PageServerLoad = async ({ locals, fetch }) => {
  const responses = await Promise.all(['/api/household/members', '/api/household/invites', '/api/plants'].map(path => fetch(path)));
  for (const response of responses) if (!response.ok) error(response.status, 'Could not load household members');
  return { identity: locals.identity!, members: await responses[0].json() as Member[], invites: await responses[1].json() as Invite[], plants: await responses[2].json() as Plant[] };
};

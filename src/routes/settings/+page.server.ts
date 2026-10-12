import { error } from '@sveltejs/kit';
import { feedSettings } from '$lib/server/feed-settings';
import { householdLocation } from '$lib/server/weather/location';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals, fetch }) => {
  if (!locals.identity) error(401, 'Login required');
  const response = await fetch('/api/household/settings');
  if (!response.ok) error(response.status, 'Could not load household settings');
  return { feedSettings: await feedSettings(locals.identity), isOwner: locals.identity.member.role === 'owner', settings: await response.json() as { misting_enabled: boolean }, ...await householdLocation(locals.identity.household.id), canEditLocation: locals.identity.member.role === 'owner' };
};

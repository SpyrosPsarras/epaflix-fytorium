import { error } from '@sveltejs/kit';
import { feedSettings } from '$lib/server/feed-settings';
import { householdLocation } from '$lib/server/weather/location';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return { feedSettings: await feedSettings(locals.identity), isOwner: locals.identity.member.role === 'owner', ...await householdLocation(locals.identity.household.id), canEditLocation: locals.identity.member.role === 'owner' };
};

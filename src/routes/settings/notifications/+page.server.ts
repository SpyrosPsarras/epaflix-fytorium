import { error } from '@sveltejs/kit';
import { notificationSettings } from '$lib/server/notifications/settings';
import { subscriptions } from '$lib/server/notifications/subscriptions';
import { config } from '$lib/server/config';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return { sitter: locals.identity.member.role === 'sitter', settings: await notificationSettings(locals.identity), devices: await subscriptions(locals.identity), timezone: config().householdTimezone, configured: !!config().vapidPublicKey };
};

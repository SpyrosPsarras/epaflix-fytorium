import { error } from '@sveltejs/kit';
import { notificationSettings } from '$lib/server/notifications/settings';
import { subscriptions } from '$lib/server/notifications/subscriptions';
import { config } from '$lib/server/config';
import { feedSettings } from '$lib/server/feed-settings';
import type { PageServerLoad } from './$types';
export const load: PageServerLoad = async ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return { ...(locals.identity.member.role !== 'sitter' ? { feedSettings: await feedSettings(locals.identity) } : {}), sitter: locals.identity.member.role === 'sitter', settings: await notificationSettings(locals.identity), devices: await subscriptions(locals.identity), timezone: config().householdTimezone, configured: !!config().vapidPublicKey };
};

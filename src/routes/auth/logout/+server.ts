import { redirect } from '@sveltejs/kit';
import { logout } from '$lib/server/auth';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async ({ cookies }) => {
  await logout(cookies);
  redirect(303, '/');
};

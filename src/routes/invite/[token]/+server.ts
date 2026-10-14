import { redirect, error } from '@sveltejs/kit';
import { INVITE_COOKIE } from '$lib/server/household';
import { cookieOptions } from '$lib/server/auth';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = ({ params, cookies }) => {
  if (!/^[A-Za-z0-9_-]{43}$/.test(params.token)) error(404, 'Invite not found');
  cookies.set(INVITE_COOKIE, params.token, cookieOptions(600));
  redirect(303, '/auth/login');
};

import { redirect, error } from '@sveltejs/kit';
import { INVITE_COOKIE } from '$lib/server/household';
import { config } from '$lib/server/config';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = ({ params, cookies }) => {
  if (!/^[A-Za-z0-9_-]{43}$/.test(params.token)) error(404, 'Invite not found');
  cookies.set(INVITE_COOKIE, params.token, { path: '/', httpOnly: true, sameSite: 'lax', secure: config().oidcRedirectUrl.protocol === 'https:', maxAge: 600 });
  redirect(303, '/auth/login');
};

import { redirect } from '@sveltejs/kit';
import { startLogin } from '$lib/server/auth';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ cookies, url }) => {
  redirect(303, (await startLogin(cookies, url)).href);
};

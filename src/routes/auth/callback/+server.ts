import { redirect } from '@sveltejs/kit';
import { finishLogin } from '$lib/server/auth';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ url, cookies }) => {
  let member: boolean;
  try { member = await finishLogin(url, cookies); }
  catch (error) {
    console.error('OIDC callback failed', { error: error instanceof Error ? error.name : 'UnknownError' });
    redirect(303, '/auth/failed');
  }
  redirect(303, member ? '/' : '/auth/not-member');
};

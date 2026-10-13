import { redirect } from '@sveltejs/kit';
import { finishLogin } from '$lib/server/auth';
import { AppLoginFailure } from '$lib/server/app-auth';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ url, cookies }) => {
  let location: string;
  try { location = await finishLogin(url, cookies); }
  catch (error) {
    if (error instanceof AppLoginFailure) {
      console.error('App OIDC callback failed', { code: error.code, name: error.cause instanceof Error ? error.cause.name : 'UnknownError' });
      redirect(303, error.location);
    }
    console.error('OIDC callback failed', {
      name: error instanceof Error ? error.name : 'UnknownError',
      message: error instanceof Error ? error.message : 'Unknown error',
      oidcError: error instanceof Error && 'error' in error && typeof error.error === 'string' ? error.error : undefined,
    });
    redirect(303, '/auth/failed');
  }
  redirect(303, location);
};

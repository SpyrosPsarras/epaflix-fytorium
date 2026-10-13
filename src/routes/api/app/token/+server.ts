import { isHttpError, json } from '@sveltejs/kit';
import { exchangeAppToken } from '$lib/server/app-auth';
import { limitAppTokens } from '$lib/server/app-rate-limit';
import { readJsonObject } from '$lib/server/json';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async ({ request, getClientAddress }) => {
  try {
    limitAppTokens('ip', getClientAddress());
    const input = await readJsonObject(request);
    const tokens = await exchangeAppToken(input);
    return tokens ? json(tokens) : json({ error: 'invalid_grant' }, { status: 400 });
  } catch (cause) {
    if (!isHttpError(cause)) throw cause;
    if (cause.status === 429) return json({ error: 'rate_limited' }, { status: 429, headers: { 'Retry-After': '60' } });
    return json({ error: 'invalid_grant' }, { status: 400 });
  }
};

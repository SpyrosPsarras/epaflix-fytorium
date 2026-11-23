import { error, json } from '@sveltejs/kit';
import { searchPlaces } from '$lib/server/weather/geocoding';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, url }) => {
  if (!locals.identity) error(401, 'Login required');
  const query = (url.searchParams.get('q') ?? '').trim();
  if (query.length < 2 || query.length > 100) return json({ errors: { q: 'Type 2 to 100 letters.' } }, { status: 422 });
  return json({ results: await searchPlaces(query, locals.identity.language) });
};

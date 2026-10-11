import { error, json } from '@sveltejs/kit';
import { searchSpecies } from '$lib/server/species';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, url }) => {
  if (!locals.identity) error(401, 'Login required');
  const query = url.searchParams.get('q')?.trim();
  if (!query || query.length > 200) error(422, 'Enter a species name up to 200 characters.');
  return json(await searchSpecies(query, locals.identity.household.id));
};

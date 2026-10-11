import { error, json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = ({ locals }) => {
  if (!locals.identity) error(401, 'Login required');
  return json(locals.identity);
};

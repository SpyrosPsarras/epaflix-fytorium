import { Readable } from 'node:stream';
import { error } from '@sveltejs/kit';
import { getPhoto } from '$lib/server/photos';
import { readPhoto } from '$lib/server/photo-storage';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  if (params.variant !== 'original' && params.variant !== 'thumb') error(404, 'Photo variant not found');
  const photo = await getPhoto(params.id, locals.identity.household.id);
  const stream = await readPhoto(params.variant === 'original' ? photo.original_key : photo.thumb_key);
  return new Response(Readable.toWeb(stream) as ReadableStream<Uint8Array>, { headers: { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' } });
};

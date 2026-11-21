import { Readable } from 'node:stream';
import { buffer } from 'node:stream/consumers';
import { error } from '@sveltejs/kit';
import { getPhoto, resizePhoto } from '$lib/server/photos';
import { readPhoto } from '$lib/server/photo-storage';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  if (params.variant !== 'original' && params.variant !== 'thumb' && params.variant !== 'display') error(404, 'Photo variant not found');
  const photo = await getPhoto(params.id, locals.identity.household.id, locals.identity.language);
  const stream = await readPhoto(params.variant === 'thumb' ? photo.thumb_key : photo.original_key);
  if (params.variant === 'display') {
    const jpeg = await resizePhoto(await buffer(stream), 1080);
    return new Response(new Uint8Array(jpeg), { headers: {
      'Content-Type': 'image/jpeg', 'Cache-Control': 'private, max-age=604800', Vary: 'Cookie, Authorization, X-App-Version',
    } });
  }
  return new Response(Readable.toWeb(stream) as ReadableStream<Uint8Array>, { headers: { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' } });
};

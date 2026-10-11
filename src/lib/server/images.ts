import { error } from '@sveltejs/kit';
import sharp from 'sharp';

export const maxPhotoBytes = 15 * 1024 * 1024;

export async function imageForm(request: Request, maxBytes = maxPhotoBytes, sizeMessage = 'Photos must be at most 15 MB.') {
  if (!request.headers.get('content-type')?.startsWith('multipart/form-data;')) error(400, 'Send a photo form.');
  // Bound the entire body before formData buffers it, including chunked uploads.
  const reader = request.body?.getReader();
  if (!reader) error(400, 'Send a photo form.');
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      size += chunk.value.byteLength;
      if (size > maxBytes + 64 * 1024) {
        // Cancelling the Node request stream destroys its socket before the 413 can be sent.
        error(413, sizeMessage);
      }
      chunks.push(chunk.value);
    }
  } finally { reader.releaseLock(); }
  try {
    return await new Response(Buffer.concat(chunks), { headers: { 'content-type': request.headers.get('content-type')! } }).formData();
  } catch { error(400, 'Send a valid photo form.'); }
}

export async function sanitiseImage(bytes: Buffer, longEdge?: number): Promise<Buffer> {
  try {
    const image = sharp(bytes, { limitInputPixels: 40_000_000, failOn: 'warning' });
    const metadata = await image.metadata();
    // Do not accept documents, SVGs or animations as camera photos.
    if (!['jpeg', 'png', 'webp', 'heif', 'tiff', 'avif'].includes(metadata.format ?? '') || (metadata.pages ?? 1) > 1) error(422, 'Choose a decodable still image.');
    const oriented = image.rotate().flatten({ background: '#ffffff' });
    if (longEdge) oriented.resize(longEdge, longEdge, { fit: 'inside', withoutEnlargement: true });
    return await oriented.jpeg({ quality: 90 }).toBuffer();
  } catch { error(422, 'Choose a decodable still image.'); }
}

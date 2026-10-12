import { error } from '@sveltejs/kit';

type JsonOptions = { maxBytes?: number; requireContentType?: boolean; message?: string };

export async function readJson(request: Request, { maxBytes = 64 * 1024, requireContentType = true, message = 'Send a valid JSON object.' }: JsonOptions = {}): Promise<unknown> {
  if (requireContentType && request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') error(400, message);
  if (Number(request.headers.get('content-length')) > maxBytes) error(413, 'JSON body is too large.');
  const reader = request.body?.getReader();
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  if (reader) {
    try {
      while (true) {
        const { done, value } = await reader.read().catch(() => error(400, message));
        if (done) break;
        bytes += value.byteLength;
        if (bytes > maxBytes) {
          await reader.cancel().catch(() => {});
          error(413, 'JSON body is too large.');
        }
        chunks.push(value);
      }
    } finally { reader.releaseLock(); }
  }
  const body = new Uint8Array(bytes);
  let offset = 0;
  for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder().decode(body)); }
  catch { error(400, message); }
}

export async function readJsonObject(request: Request, options: JsonOptions = {}): Promise<Record<string, unknown>> {
  const body = await readJson(request, options);
  if (!body || typeof body !== 'object' || Array.isArray(body)) error(400, options.message ?? 'Send a valid JSON object.');
  return body as Record<string, unknown>;
}

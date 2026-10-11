import { error } from '@sveltejs/kit';

export async function readJsonObject(request: Request): Promise<Record<string, unknown>> {
  let body;
  try {
    if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') throw new Error();
    body = await request.json();
  } catch { error(400, 'Send a valid JSON object.'); }
  if (!body || typeof body !== 'object' || Array.isArray(body)) error(400, 'Send a valid JSON object.');
  return body;
}

import { db } from '$lib/server/db';
import { json } from '@sveltejs/kit';

export async function GET() {
  try {
    await db().query('SELECT 1');
    return json({ status: 'ok' });
  } catch {
    return json({ status: 'unavailable' }, { status: 503 });
  }
}

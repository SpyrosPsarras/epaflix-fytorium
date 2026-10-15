import { error } from '@sveltejs/kit';
import { db } from '../db';
import { sanitiseImage } from '../images';
import { downloadImage } from '../net/download-image';

const pending = new Map<string, Promise<Buffer>>();

export async function coverThumbnail(id: string, household: string): Promise<Buffer> {
  const key = household + ':' + id;
  const existing = pending.get(key);
  if (existing) return existing;
  const loading = fetchThumbnail(id, household);
  pending.set(key, loading);
  try { return await loading; }
  finally { pending.delete(key); }
}

async function fetchThumbnail(id: string, household: string): Promise<Buffer> {
  const result = await db().query('SELECT mapped, cover_thumbnail FROM import_staging WHERE id = $1 AND household_id = $2', [id, household]);
  const row = result.rows[0];
  if (!row?.mapped.cover_url) error(404, 'Planta cover not found');
  if (row.cover_thumbnail) return row.cover_thumbnail;
  const thumbnail = await sanitiseImage(await downloadCover(row.mapped.cover_url), 400);
  await db().query('UPDATE import_staging SET cover_thumbnail = $1 WHERE id = $2 AND household_id = $3', [thumbnail, id, household]);
  return thumbnail;
}

export function downloadCover(value: string): Promise<Buffer> {
  return downloadImage(value, 'Planta cover');
}

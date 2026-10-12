import { randomUUID } from 'node:crypto';
import { error } from '@sveltejs/kit';
import sharp from 'sharp';
import { healthValues, type PhotoUpdate } from '$lib/photos';
import type { Identity } from './auth';
import { db } from './db';
import { getPlant, isUuid } from './plants';
import { now, calendarDate } from './clock';
import { storePhoto, removePhotos } from './photo-storage';
import { imageForm, maxPhotoBytes, sanitiseImage } from './images';

export async function photoThumbnail(original: Buffer): Promise<Buffer> {
  try { return await sharp(original).resize(400, 400, { fit: 'inside', withoutEnlargement: true }).jpeg({ quality: 80 }).toBuffer(); }
  catch { error(422, 'Choose a decodable still image.'); }
}

// Callers own rollback cleanup for both keys, including a partial storage failure.
export async function storePhotoPair(keys: string[], original: Buffer, thumb: Buffer) {
  await storePhoto(keys[0], original);
  await storePhoto(keys[1], thumb);
}

export function photoToday() {
  return calendarDate(now());
}
const photoSelect = `SELECT u.id, u.plant_id, u.original_key, u.thumb_key, u.note, u.health,
  to_char(u.taken_on, 'YYYY-MM-DD') AS taken_on, u.created_at, u.created_by, u.is_cover,
  coalesce(nullif(p.nickname, ''), s.canonical_name) AS plant_name
  FROM photo_updates u JOIN plants p ON p.id = u.plant_id AND p.household_id = u.household_id
  JOIN species s ON s.gbif_key = p.gbif_key`;
export async function listPhotos(household: string, plant?: string): Promise<PhotoUpdate[]> {
  if (plant) await getPlant(plant, household);
  const result = await db().query(photoSelect + ' WHERE u.household_id = $1 AND ($2::uuid IS NULL OR u.plant_id = $2) ORDER BY u.taken_on DESC, u.created_at DESC, u.id DESC', [household, plant ?? null]);
  return result.rows;
}
export async function getPhoto(id: string, household: string): Promise<PhotoUpdate> {
  if (!isUuid(id)) error(404, 'Photo update not found');
  const result = await db().query(photoSelect + ' WHERE u.id = $1 AND u.household_id = $2', [id, household]);
  if (!result.rowCount) error(404, 'Photo update not found');
  return result.rows[0];
}

export async function addPhoto(identity: Identity, plant: string, request: Request) {
  const household = identity.household.id;
  await getPlant(plant, household);
  const form = await imageForm(request);
  const file = form.get('photo');
  if (!(file instanceof File) || !file.size) error(422, 'Choose an image.');
  if (file.size > maxPhotoBytes) error(413, 'Photos must be at most 15 MB.');
  const note = form.get('note') ?? '';
  const health = form.get('health') || null;
  const today = photoToday();
  const takenOn = form.get('taken_on') || today;
  if (typeof note !== 'string' || note.length > 5000) error(422, 'Notes must be at most 5000 characters.');
  if (health !== null && !healthValues.some(value => value === health)) error(422, 'Choose a valid health value.');
  if (typeof takenOn !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(takenOn) || takenOn < '0001-01-01' || !Number.isFinite(Date.parse(takenOn)) || new Date(takenOn).toISOString().slice(0, 10) !== takenOn || takenOn > today) error(422, 'Choose a valid date that is not in the future.');
  const original = await sanitiseImage(Buffer.from(await file.arrayBuffer()));
  const thumb = await photoThumbnail(original);
  const id = randomUUID();
  const prefix = `photos/${household}/${plant}/${id}`;
  const keys = [prefix + '-original.jpg', prefix + '-thumb.jpg'];
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    const locked = await client.query('SELECT id FROM plants WHERE id = $1 AND household_id = $2 FOR UPDATE', [plant, household]);
    if (!locked.rowCount) error(404, 'Plant not found');
    await storePhotoPair(keys, original, thumb);
    await client.query('INSERT INTO photo_updates (id, household_id, plant_id, original_key, thumb_key, note, health, taken_on, validated_on, created_by, created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)', [id, household, plant, ...keys, note, health, takenOn, today, identity.member.id, now()]);
    const newer = await client.query('SELECT id FROM photo_updates WHERE plant_id = $1 AND household_id = $2 AND taken_on > $3 LIMIT 1', [plant, household, takenOn]);
    if (health !== null && !newer.rowCount) await client.query('UPDATE plants SET health = $1 WHERE id = $2 AND household_id = $3', [health, plant, household]);
    await client.query('COMMIT');
  } catch (cause) {
    // A dropped DB connection must not prevent S3 cleanup or replace the upload error.
    try { await client.query('ROLLBACK'); }
    catch { console.error('Photo upload rollback failed.'); }
    try { await removePhotos(keys); }
    catch { console.error('Photo upload object cleanup failed.'); }
    throw cause;
  } finally { client.release(); }
  return getPhoto(id, household);
}

export async function deletePhoto(id: string, household: string) {
  const photo = await getPhoto(id, household);
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    await client.query('SELECT id FROM plants WHERE id = $1 AND household_id = $2 FOR UPDATE', [photo.plant_id, household]);
    // Keep the row on storage failure so the member can retry deletion.
    await removePhotos([photo.original_key, photo.thumb_key]);
    await client.query('DELETE FROM photo_updates WHERE id = $1 AND household_id = $2', [id, household]);
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
}

export async function setCover(id: string, household: string) {
  const photo = await getPhoto(id, household);
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    // Serialize cover changes with uploads and deletion for this plant.
    await client.query('SELECT id FROM plants WHERE id = $1 AND household_id = $2 FOR UPDATE', [photo.plant_id, household]);
    await client.query('UPDATE photo_updates SET is_cover = false WHERE plant_id = $1 AND household_id = $2 AND is_cover', [photo.plant_id, household]);
    const result = await client.query('UPDATE photo_updates SET is_cover = true WHERE id = $1 AND household_id = $2 RETURNING id', [id, household]);
    if (!result.rowCount) error(404, 'Photo update not found');
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
  return getPhoto(id, household);
}

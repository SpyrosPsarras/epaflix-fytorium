import { error } from '@sveltejs/kit';
import type { HouseholdLocation } from '$lib/weather';
import { db } from '../db';
import { now } from '../clock';

export async function readWeatherJson(request: Request): Promise<Record<string, unknown>> {
  let body;
  try {
    if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') throw new Error();
    body = await request.json();
  } catch { error(400, 'Send a valid JSON object.'); }
  if (!body || typeof body !== 'object' || Array.isArray(body)) error(400, 'Send a valid JSON object.');
  return body;
}
export async function householdLocation(household: string): Promise<{ location: HouseholdLocation | null }> {
  const { rows: [row] } = await db().query('SELECT latitude, longitude, location_label FROM households WHERE id = $1', [household]);
  return { location: row.latitude === null ? null : { latitude: row.latitude, longitude: row.longitude, label: row.location_label } };
}
export async function setLocation(household: string, value: unknown) {
  let location: HouseholdLocation | null = null;
  if (value !== null) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) error(422, 'Set a location object or null.');
    const v = value as Record<string, unknown>;
    if (typeof v.latitude !== 'number' || !Number.isFinite(v.latitude) || v.latitude < -90 || v.latitude > 90) error(422, 'Latitude must be a number between -90 and 90.');
    if (typeof v.longitude !== 'number' || !Number.isFinite(v.longitude) || v.longitude < -180 || v.longitude > 180) error(422, 'Longitude must be a number between -180 and 180.');
    if (v.label != null && (typeof v.label !== 'string' || v.label.trim().length > 200)) error(422, 'Place label must be text up to 200 characters.');
    location = { latitude: v.latitude, longitude: v.longitude, label: typeof v.label === 'string' ? v.label.trim() || null : null };
  }
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    const changed = await client.query('UPDATE households SET latitude = $2, longitude = $3, location_label = $4, location_revision = location_revision + 1 WHERE id = $1 AND (latitude IS DISTINCT FROM $2 OR longitude IS DISTINCT FROM $3) RETURNING id', [household, location?.latitude ?? null, location?.longitude ?? null, location?.label ?? null]);
    if (changed.rowCount) {
      await client.query('DELETE FROM weather_cache WHERE household_id = $1', [household]);
      await client.query('UPDATE weather_events SET resolved_at = $2 WHERE household_id = $1 AND resolved_at IS NULL', [household, now()]);
    } else await client.query('UPDATE households SET location_label = $2 WHERE id = $1', [household, location?.label ?? null]);
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
  return householdLocation(household);
}

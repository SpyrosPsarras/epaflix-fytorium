import { error, json } from '@sveltejs/kit';
import { db } from './db';
import { plantChoices, plantBooleans, plantNumbers, type PlantInput } from '$lib/plants';
import { now } from './clock';
import { healthValues } from '$lib/photos';

export const plantFields = ['site_id', 'nickname', 'cultivar', 'birthday', 'pot_type', 'pot_size_cm', 'drainage', 'soil_type', 'plant_size', 'growth_stage', 'window_distance_cm', 'grow_light', 'near_ac', 'near_heater', 'fertilizer_type', 'health'] as const;
export const plantSelect = `SELECT p.id, p.household_id, p.site_id, p.gbif_key, p.nickname, p.cultivar,
  to_char(p.birthday, 'YYYY-MM-DD') AS birthday, p.created_at, p.attributes_as_of,
  p.status, to_char(p.graveyard_on, 'YYYY-MM-DD') AS graveyard_on, p.pot_type, p.pot_size_cm, p.drainage, p.soil_type, p.plant_size, p.growth_stage,
  p.window_distance_cm, p.grow_light, p.near_ac, p.near_heater, p.fertilizer_type, p.misting_enabled, p.health,
  (SELECT u.id FROM photo_updates u WHERE u.plant_id = p.id AND u.household_id = p.household_id AND u.is_cover) AS cover_photo_id,
  s.scientific_name, s.canonical_name, s.rank, coalesce(nullif(p.nickname, ''), s.canonical_name) AS display_name, t.name AS site_name
  FROM plants p JOIN species s ON s.gbif_key = p.gbif_key LEFT JOIN sites t ON t.id = p.site_id AND t.household_id = p.household_id`;
export function isUuid(id: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id);
}
export async function getPlant(id: string, household: string) {
  if (!isUuid(id)) error(404, 'Plant not found');
  const result = await db().query(plantSelect + ' WHERE p.id = $1 AND p.household_id = $2', [id, household]);
  if (!result.rowCount) error(404, 'Plant not found');
  return result.rows[0];
}
export async function readPlant(request: Request, household: string, allowNoSite = false): Promise<(Omit<PlantInput, 'site_id'> & { site_id: string | null } & { search_id?: unknown; gbif_key?: unknown; confirmed?: unknown }) | Response> {
  let input;
  try {
    if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') throw new Error('Expected JSON');
    input = await request.json();
  } catch { return json({ errors: { _form: 'Send a valid JSON object.' } }, { status: 400 }); }
  if (!input || typeof input !== 'object' || Array.isArray(input)) return json({ errors: { _form: 'Send a valid JSON object.' } }, { status: 400 });
  const errors: Record<string, string> = {};
  for (const [field, values] of Object.entries(plantChoices)) {
    if (!values.some(value => value === input[field])) errors[field] = 'Choose one of: ' + values.join(', ') + '.';
  }
  for (const field of plantBooleans) if (typeof input[field] !== 'boolean') errors[field] = 'Choose yes or no.';
  for (const field of plantNumbers) {
    input[field] ??= null;
    const value = input[field];
    if (value !== null && (typeof value !== 'number' || !Number.isInteger(value) || value <= 0 || value > 2147483647)) errors[field] = 'Enter a positive whole number or leave blank.';
  }
  for (const field of ['nickname', 'cultivar']) {
    input[field] ??= null;
    if (input[field] !== null && typeof input[field] !== 'string') errors[field] = 'Enter text or leave blank.';
    else input[field] = input[field]?.trim() || null;
  }
  input.health ??= null;
  if (input.health !== null && !healthValues.some(value => value === input.health)) errors.health = 'Choose a valid health value or leave blank.';
  input.birthday ??= null;
  if (input.birthday !== null) {
    const value = input.birthday;
    if (typeof value !== 'string' || value < '0001-01-01' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value)) || new Date(value).toISOString().slice(0, 10) !== value || value > now().toISOString().slice(0, 10)) {
      errors.birthday = 'Enter a valid date that is not in the future.';
    }
  }
  if (!(allowNoSite && input.site_id === null)) {
    if (typeof input.site_id !== 'string' || !isUuid(input.site_id)) errors.site_id = 'Choose a site in your household.';
    else {
      const site = await db().query('SELECT id FROM sites WHERE id = $1 AND household_id = $2', [input.site_id, household]);
      if (!site.rowCount) errors.site_id = 'Choose a site in your household.';
    }
  }
  if (Object.keys(errors).length) return json({ errors }, { status: 422 });
  return input;
}

export function writeError(cause: unknown): Response | undefined {
  if (cause instanceof Error && 'code' in cause && cause.code === '23503') return json({ errors: { site_id: 'Choose a site in your household.' } }, { status: 422 });
}

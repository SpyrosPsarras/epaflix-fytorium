import { error, isHttpError, json } from '@sveltejs/kit';
import { readJsonObject } from './json';
import { db } from './db';
import { plantChoices, plantBooleans, plantNumbers, type PlantInput } from '$lib/plants';
import { now } from './clock';
import { healthValues } from '$lib/photos';

export const plantFields = ['site_id', 'nickname', 'cultivar', 'birthday', 'pot_type', 'pot_size_cm', 'drainage', 'soil_type', 'plant_size', 'growth_stage', 'window_distance_cm', 'grow_light', 'near_ac', 'near_heater', 'fertilizer_type', 'health'] as const;
export function plantSelectFor(language: 'en' | 'nb' | 'el'): string {
  if (!['en', 'nb', 'el'].includes(language)) throw new Error('Unsupported plant name language');
  return `SELECT p.id, p.household_id, p.site_id, p.gbif_key, p.nickname, p.cultivar,
  to_char(p.birthday, 'YYYY-MM-DD') AS birthday, p.created_at, p.attributes_as_of,
  p.status, to_char(p.graveyard_on, 'YYYY-MM-DD') AS graveyard_on, p.pot_type, p.pot_size_cm, p.drainage, p.soil_type, p.plant_size, p.growth_stage,
  p.window_distance_cm, p.grow_light, p.near_ac, p.near_heater, p.fertilizer_type, p.misting_enabled, p.health,
  (SELECT u.id FROM photo_updates u WHERE u.plant_id = p.id AND u.household_id = p.household_id AND u.is_cover) AS cover_photo_id,
  s.scientific_name, s.canonical_name, s.rank, plant_display_name(p.nickname, p.gbif_key, '${language}') AS display_name, t.name AS site_name
  FROM plants p JOIN species s ON s.gbif_key = p.gbif_key LEFT JOIN sites t ON t.id = p.site_id AND t.household_id = p.household_id`;
}
export function isUuid(id: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id);
}
export async function getPlant(id: string, household: string, language: 'en' | 'nb' | 'el') {
  if (!isUuid(id)) error(404, 'Plant not found');
  const result = await db().query(plantSelectFor(language) + ' WHERE p.id = $1 AND p.household_id = $2', [id, household]);
  if (!result.rowCount) error(404, 'Plant not found');
  return result.rows[0];
}
export function potErrors(input: Record<string, unknown>): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of ['pot_type', 'soil_type'] as const) {
    const values = plantChoices[field];
    if (!values.some(value => value === input[field])) errors[field] = 'Choose one of: ' + values.join(', ') + '.';
  }
  const size = input.pot_size_cm ?? null;
  if (size !== null && (typeof size !== 'number' || !Number.isInteger(size) || size <= 0 || size > 2147483647)) errors.pot_size_cm = 'Enter a positive whole number or leave blank.';
  return errors;
}
export async function readPlant(request: Request, household: string, allowNoSite = false): Promise<(Omit<PlantInput, 'site_id'> & { site_id: string | null } & { search_id?: unknown; gbif_key?: unknown; confirmed?: unknown }) | Response> {
  let input: Record<string, unknown>;
  try { input = await readJsonObject(request); }
  catch (cause) {
    if (!isHttpError(cause) || cause.status !== 400) throw cause;
    return json({ errors: { _form: 'Send a valid JSON object.' } }, { status: 400 });
  }
  const errors = potErrors(input);
  if ('score' in input) errors.score = 'Identification scores are server-owned. Choose a candidate from the results.';
  for (const [field, values] of Object.entries(plantChoices)) {
    if (field === 'pot_type' || field === 'soil_type') continue;
    if (!values.some(value => value === input[field])) errors[field] = 'Choose one of: ' + values.join(', ') + '.';
  }
  for (const field of plantBooleans) if (typeof input[field] !== 'boolean') errors[field] = 'Choose yes or no.';
  for (const field of plantNumbers) {
    input[field] ??= null;
    if (field === 'pot_size_cm') continue;
    const value = input[field];
    if (value !== null && (typeof value !== 'number' || !Number.isInteger(value) || value <= 0 || value > 2147483647)) errors[field] = 'Enter a positive whole number or leave blank.';
  }
  for (const field of ['nickname', 'cultivar']) {
    input[field] ??= null;
    if (input[field] !== null && typeof input[field] !== 'string') errors[field] = 'Enter text or leave blank.';
    else input[field] = typeof input[field] === 'string' ? input[field].trim() || null : null;
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
  return input as Omit<PlantInput, 'site_id'> & { site_id: string | null };
}

export function writeError(cause: unknown): Response | undefined {
  if (!(cause instanceof Error) || !('code' in cause)) return;
  if (cause.code === '23503') return json({ errors: { site_id: 'Choose a site in your household.' } }, { status: 422 });
  if (cause.code === '23514') {
    const errors = 'constraint' in cause && cause.constraint === 'plants_active_site'
      ? { site_id: 'Active plants must have a site.' }
      : { _form: 'Plant data does not meet required constraints.' };
    return json({ errors }, { status: 422 });
  }
}

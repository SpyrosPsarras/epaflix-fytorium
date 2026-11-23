import { error, isHttpError, json } from '@sveltejs/kit';
import type pg from 'pg';
import { readJsonObject } from './json';
import { bearerDevice } from './app-auth';
import { locations, lightLevels, humidityLevels, type SiteInput } from '$lib/sites';

export function checkOrigin(request: Request) {
  if (bearerDevice(request)) return;
  const origin = request.headers.get('origin');
  if (origin && origin !== new URL(request.url).origin) error(403, 'Cross-origin request refused');
}

// Site names are unique per household under Planta import's rule; a site keeping its own name is never refused, so older duplicates still save.
export async function siteNameTaken(client: pg.PoolClient, household: string, name: string, siteId: string | null = null) {
  await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
  const taken = await client.query<{ name: string }>(
    `SELECT btrim(name) AS name FROM sites WHERE household_id = $1 AND lower(btrim(name)) = lower(btrim($2))
     AND ($3::uuid IS NULL OR (id <> $3 AND NOT EXISTS (SELECT 1 FROM sites own WHERE own.id = $3 AND own.household_id = $1 AND lower(btrim(own.name)) = lower(btrim($2)))))
     ORDER BY created_at, id LIMIT 1`,
    [household, name, siteId],
  );
  return taken.rows[0] ? json({ errors: { name: `You already have a site called ${taken.rows[0].name}.` } }, { status: 422 }) : null;
}

export async function readSite(request: Request): Promise<SiteInput | Response> {
  let input: Record<string, unknown>;
  try { input = await readJsonObject(request); }
  catch (cause) {
    if (!isHttpError(cause) || cause.status !== 400) throw cause;
    return json({ errors: { _form: 'Send a valid JSON object.' } }, { status: 400 });
  }
  const site = input;
  const errors: Record<string, string> = {};
  if (typeof site.name !== 'string' || !site.name.trim()) errors.name = 'Enter a site name.';
  else site.name = site.name.trim();
  for (const [field, values] of [['location', locations], ['light', lightLevels], ['humidity', humidityLevels]] as const) {
    if (!values.some(value => value === site[field])) errors[field] = `Choose one of: ${values.join(', ')}.`;
  }
  for (const field of ['grow_light', 'draft']) {
    if (typeof site[field] !== 'boolean') errors[field] = 'Choose yes or no.';
  }
  for (const period of ['warm', 'cold']) {
    const min = `${period}_min_c`;
    const max = `${period}_max_c`;
    for (const field of [min, max]) {
      site[field] ??= null;
      const value = site[field];
      if (value !== null && (typeof value !== 'number' || !Number.isInteger(value) || value < -2147483648 || value > 2147483647)) {
        errors[field] = 'Enter a whole number of °C or leave blank.';
      }
    }
    if (typeof site[min] === 'number' && typeof site[max] === 'number' && site[min] > site[max]) {
      errors[min] = 'Minimum must not exceed maximum.';
    }
  }
  if (Object.keys(errors).length) return json({ errors }, { status: 422 });
  return site as SiteInput;
}

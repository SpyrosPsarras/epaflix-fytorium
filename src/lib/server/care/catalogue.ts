import { error } from '@sveltejs/kit';
import type { PoolClient } from 'pg';
import { careNames, validCareValue, type CareProfile } from '$lib/care';
import type { ScheduleInput } from '$lib/schedule/engine';
import { careRuleDefaults } from '$lib/schedule/rules';
import { db } from '../db';
import { now } from '../clock';
import { careSources } from './registry';

export async function enqueueCareFetch(client: PoolClient, gbifKey: number) {
  for (const { name } of careSources()) await client.query('INSERT INTO care_fetch_jobs (gbif_key, source, next_at) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING', [gbifKey, name, now()]);
}
export async function getCareSpecies(key: string | number, household: string) {
  if (!/^\d+$/.test(String(key)) || !Number.isSafeInteger(Number(key)) || Number(key) > 2147483647) error(404, 'Species not found');
  const result = await db().query('SELECT s.* FROM species s WHERE s.gbif_key = $1 AND EXISTS (SELECT 1 FROM plants p WHERE p.gbif_key = s.gbif_key AND p.household_id = $2)', [Number(key), household]);
  if (!result.rowCount) error(404, 'Species not found');
  return result.rows[0];
}

export async function effectiveCareProfiles(gbifKeys: number[], household: string, client: Pick<PoolClient, 'query'> = db()): Promise<Map<number, ScheduleInput['careProfile']>> {
  const keys = [...new Set(gbifKeys)];
  const [cached, overrides] = await Promise.all([
    client.query('SELECT gbif_key, source, fields, fetched_at FROM care_source_cache WHERE gbif_key = ANY($1::integer[])', [keys]),
    client.query('SELECT gbif_key, field, value, updated_at FROM care_overrides WHERE gbif_key = ANY($1::integer[]) AND household_id = $2', [keys, household]),
  ]);
  const profiles = new Map(keys.map(key => [key, {} as ScheduleInput['careProfile']]));
  // Lower-priority sources go first; missing values stay out so the engine applies its rules.
  for (const { name } of careSources().reverse()) {
    for (const row of cached.rows) {
      if (row.source !== name) continue;
      const profile = profiles.get(row.gbif_key)!;
      for (const field of careNames) if (validCareValue(field, row.fields[field])) profile[field] = { value: row.fields[field], source: name, fetchedAt: row.fetched_at.toISOString() };
    }
  }
  for (const row of overrides.rows) if (validCareValue(row.field, row.value)) profiles.get(row.gbif_key)![row.field] = { value: row.value, source: 'override', fetchedAt: row.updated_at.toISOString() };
  return profiles;
}
export async function speciesCare(key: string, household: string) {
  const species = await getCareSpecies(key, household);
  const [profiles, jobs, states, plantbookMatch] = await Promise.all([
    effectiveCareProfiles([species.gbif_key], household),
    db().query('SELECT source, status, attempts, next_at FROM care_fetch_jobs WHERE gbif_key = $1', [species.gbif_key]),
    db().query('SELECT source, status FROM care_source_limits'),
    db().query('SELECT status, pid, checked_at FROM open_plantbook_matches WHERE gbif_key = $1', [species.gbif_key]),
  ]);
  const profile = Object.fromEntries(careNames.map(field => [field, { value: null, source: 'unknown', fetchedAt: null }])) as CareProfile;
  for (const [field, rule] of Object.entries(careRuleDefaults)) profile[field as keyof CareProfile] = { ...rule, fetchedAt: null };
  Object.assign(profile, profiles.get(species.gbif_key));
  const fetches = careSources().map(({ name, source, matchesByName }) => {
    const job = jobs.rows.find(row => row.source === name);
    const state = states.rows.find(row => row.source === name);
    return { ...job, source: name, matchStatus: matchesByName ? plantbookMatch.rows[0]?.status as string | undefined : undefined, pid: matchesByName ? plantbookMatch.rows[0]?.pid as string | null | undefined : undefined, status: !source.configured ? 'disabled' : state?.status === 'auth_failed' ? 'auth_failed' : job?.status ?? 'not_queued' };
  });
  return { species, profile, fetches, fetch: fetches[0] ?? null, openPlantbookMatch: plantbookMatch.rows[0] ?? null };
}
export async function unmatchedSpecies(household: string) {
  const result = await db().query("SELECT s.gbif_key, s.canonical_name, m.status FROM open_plantbook_matches m JOIN species s USING (gbif_key) WHERE m.status IN ('no_match', 'ambiguous') AND EXISTS (SELECT 1 FROM plants p WHERE p.gbif_key = s.gbif_key AND p.household_id = $1) ORDER BY s.canonical_name, s.gbif_key", [household]);
  return result.rows as { gbif_key: number; canonical_name: string; status: 'no_match' | 'ambiguous' }[];
}
export async function retryPlantbookMatch(key: string, household: string) {
  const species = await getCareSpecies(key, household);
  const source = careSources().find(source => source.matchesByName)!;
  if (!source.source.configured) error(409, 'Open Plantbook is not configured');
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    const result = await client.query("INSERT INTO care_fetch_jobs (gbif_key, source, next_at) VALUES ($1, 'open_plantbook', $2) ON CONFLICT (gbif_key, source) DO UPDATE SET status = 'pending', source_key = NULL, attempts = 0, next_at = EXCLUDED.next_at WHERE care_fetch_jobs.status <> 'fetching' RETURNING gbif_key", [species.gbif_key, now()]);
    if (!result.rowCount) error(409, 'A fetch is already running');
    await client.query('DELETE FROM open_plantbook_matches WHERE gbif_key = $1', [species.gbif_key]);
    await client.query("DELETE FROM care_source_cache WHERE gbif_key = $1 AND source = 'open_plantbook'", [species.gbif_key]);
    await client.query("UPDATE care_source_limits SET status = 'active' WHERE source = 'open_plantbook' AND status = 'auth_failed'");
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
}
export async function setCareOverride(key: string, field: string, value: unknown, household: string) {
  const species = await getCareSpecies(key, household);
  if (!validCareValue(field, value)) error(422, 'Invalid value for ' + field);
  await db().query('INSERT INTO care_overrides (household_id, gbif_key, field, value, updated_at) VALUES ($1, $2, $3, $4, $5) ON CONFLICT (household_id, gbif_key, field) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at', [household, species.gbif_key, field, JSON.stringify(value), now()]);
  return speciesCare(key, household);
}
export async function removeCareOverride(key: string, field: string, household: string) {
  const species = await getCareSpecies(key, household);
  if (!careNames.includes(field as keyof CareProfile)) error(422, 'Unknown care field');
  await db().query('DELETE FROM care_overrides WHERE household_id = $1 AND gbif_key = $2 AND field = $3', [household, species.gbif_key, field]);
}
export async function refreshCare(key: string, household: string) {
  const species = await getCareSpecies(key, household);
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    for (const { name, source } of careSources()) {
      const result = await client.query("INSERT INTO care_fetch_jobs (gbif_key, source, next_at) VALUES ($1, $2, $3) ON CONFLICT (gbif_key, source) DO UPDATE SET status = 'pending', attempts = 0, next_at = EXCLUDED.next_at WHERE care_fetch_jobs.status <> 'fetching' RETURNING gbif_key", [species.gbif_key, name, now()]);
      if (!result.rowCount) error(409, 'A fetch is already running');
      if (source.configured) await client.query("UPDATE care_source_limits SET status = 'active' WHERE source = $1 AND status = 'auth_failed'", [name]);
    }
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
}

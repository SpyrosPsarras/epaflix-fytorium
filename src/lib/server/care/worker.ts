import { randomUUID } from 'node:crypto';
import { db, withTransaction } from '../db';
import { config } from '../config';
import { now } from '../clock';
import { careSources, type SourceName } from './registry';
import type { CareSource, SourceLimits } from './source';

// One leased job per source per tick. Claim IDs fence stale workers.
async function runSource(name: SourceName, source: CareSource, limits: SourceLimits) {
  await db().query("INSERT INTO care_source_limits (source, status) VALUES ($1, $2) ON CONFLICT (source) DO UPDATE SET status = CASE WHEN EXCLUDED.status = 'disabled' THEN 'disabled' WHEN care_source_limits.status = 'disabled' THEN 'active' ELSE care_source_limits.status END", [name, source.configured ? 'active' : 'disabled']);
  const state = await db().query('SELECT status FROM care_source_limits WHERE source = $1', [name]);
  if (state.rows[0].status !== 'active') return;
  const time = now();
  // Persist the hourly check so multiple workers do not each scan every two seconds.
  const refresh = await db().query('UPDATE care_source_limits SET last_refresh_check = $2 WHERE source = $1 AND (last_refresh_check IS NULL OR last_refresh_check <= $3) RETURNING source', [name, time, new Date(time.getTime() - 3600000)]);
  if (refresh.rowCount) await db().query("UPDATE care_fetch_jobs j SET status = 'pending', attempts = 0, next_at = $2 FROM care_source_cache c WHERE j.gbif_key = c.gbif_key AND j.source = c.source AND j.source = $1 AND j.status = 'complete' AND c.fetched_at <= $3", [name, time, new Date(time.getTime() - 30 * 86400000)]);
  const claim = randomUUID();
  const job = await withTransaction(async (client) => {
    const result = await client.query("SELECT j.*, s.canonical_name FROM care_fetch_jobs j JOIN species s USING (gbif_key) WHERE j.source = $2 AND ((j.status = 'pending' AND j.next_at <= $1) OR (j.status = 'fetching' AND j.lease_until <= $1)) ORDER BY j.next_at, j.gbif_key LIMIT 1 FOR UPDATE OF j SKIP LOCKED", [time, name]);
    let job = result.rows[0];
    if (job) {
      if (job.attempts >= limits.maxAttempts) {
        await client.query("UPDATE care_fetch_jobs SET status = 'failed', lease_until = NULL WHERE gbif_key = $1 AND source = $2", [job.gbif_key, name]);
        job = undefined;
      } else {
        await client.query("UPDATE care_fetch_jobs SET status = 'fetching', attempts = attempts + 1, lease_until = $2, claim_id = $3 WHERE gbif_key = $1 AND source = $4", [job.gbif_key, new Date(time.getTime() + 60000), claim, name]);
      }
    }
    return job;
  });
  if (!job) return;
  const result = await source.fetch({ gbif_key: job.gbif_key, canonical_name: job.canonical_name, sourceKey: job.source_key });
  let authPaused = false;
  await withTransaction(async (finish) => {
    const owned = await finish.query("SELECT gbif_key FROM care_fetch_jobs WHERE gbif_key = $1 AND source = $3 AND claim_id = $2 AND status = 'fetching' FOR UPDATE", [job.gbif_key, claim, name]);
    if (owned.rowCount) {
      if (result === 'auth_failed') {
        const paused = await finish.query("UPDATE care_source_limits SET status = 'auth_failed' WHERE source = $1 AND status <> 'auth_failed' RETURNING source", [name]);
        authPaused = Boolean(paused.rowCount);
        await finish.query("UPDATE care_fetch_jobs SET status = 'pending', attempts = attempts - 1, lease_until = NULL WHERE gbif_key = $1 AND source = $2", [job.gbif_key, name]);
      } else if (result === 'not_available') {
        await finish.query("UPDATE care_fetch_jobs SET status = 'not_available', lease_until = NULL WHERE gbif_key = $1 AND source = $2", [job.gbif_key, name]);
      } else if ('retryLater' in result) {
        const status = !result.quota && job.attempts + 1 >= limits.maxAttempts ? 'failed' : 'pending';
        // Quota waits are not failed attempts, including a search whose detail must wait.
        await finish.query("UPDATE care_fetch_jobs SET status = $2, next_at = $3, source_key = COALESCE($4, source_key), attempts = attempts - $5, lease_until = NULL WHERE gbif_key = $1 AND source = $6", [job.gbif_key, status, result.retryLater, result.key ?? null, result.quota ? 1 : 0, name]);
      } else {
        await finish.query('INSERT INTO care_source_cache (gbif_key, source, source_key, fields, fetched_at) VALUES ($1, $2, $3, $4, $5) ON CONFLICT (gbif_key, source) DO UPDATE SET source_key = EXCLUDED.source_key, fields = EXCLUDED.fields, fetched_at = EXCLUDED.fetched_at', [job.gbif_key, name, result.key, JSON.stringify(result.fields), result.fetchedAt]);
        await finish.query("UPDATE care_fetch_jobs SET status = 'complete', source_key = $2, lease_until = NULL WHERE gbif_key = $1 AND source = $3", [job.gbif_key, result.key, name]);
      }
    }
  });
  if (authPaused) console.error('Care source ' + name + ' paused after an authentication failure. Check its credentials before requesting a refresh.');
}
export async function runCareWorkerOnce() {
  for (const { name, source, limits } of careSources()) await runSource(name, source, limits);
}
let started = false;
export function startCareWorker() {
  if (started || !config().perenualWorkerEnabled) return;
  started = true;
  let running = false;
  setInterval(async () => {
    if (running) return;
    running = true;
    try { await runCareWorkerOnce(); }
    catch { console.error('Care fetch worker could not finish a job. It will recover after its lease expires.'); }
    finally { running = false; }
  }, 2000).unref();
}

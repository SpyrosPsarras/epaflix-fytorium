import { db } from '../db';
import { now } from '../clock';
import type { SourceLimits } from './source';

export async function reserveRequest(source: string, limits: SourceLimits): Promise<Date | null> {
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    const state = await client.query('SELECT backoff_until, status FROM care_source_limits WHERE source = $1 FOR UPDATE', [source]);
    const time = now();
    const day = new Date(time); day.setUTCHours(0, 0, 0, 0);
    const minute = new Date(time.getTime() - 60000);
    const usage = await client.query('SELECT count(*) FILTER (WHERE requested_at >= $1 AND requested_at < $3)::integer AS daily, count(*) FILTER (WHERE requested_at > $2 AND requested_at <= $4)::integer AS minute, min(requested_at) FILTER (WHERE requested_at > $2 AND requested_at <= $4) AS oldest FROM care_source_requests WHERE source = $5', [day, minute, new Date(day.getTime() + 86400000), time, source]);
    let wait = state.rows[0].backoff_until as Date | null;
    if (wait && wait <= time) wait = null;
    if (state.rows[0].status !== 'active') wait = new Date(time.getTime() + 60000);
    if (usage.rows[0].daily >= limits.daily) wait = new Date(day.getTime() + 86400000);
    if (usage.rows[0].minute >= limits.perMinute) {
      const nextMinute = new Date(usage.rows[0].oldest.getTime() + 60000);
      if (!wait || nextMinute > wait) wait = nextMinute;
    }
    if (!wait) await client.query('INSERT INTO care_source_requests (source, requested_at) VALUES ($1, $2)', [source, time]);
    await client.query('DELETE FROM care_source_requests WHERE source = $1 AND requested_at < $2', [source, new Date(day.getTime() - 86400000)]);
    await client.query('COMMIT');
    return wait;
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
}
export async function backoff(source: string, until: Date) {
  await db().query('UPDATE care_source_limits SET backoff_until = GREATEST(backoff_until, $2) WHERE source = $1', [source, until]);
}

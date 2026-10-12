import type pg from 'pg';
import { db } from '../db';
import { now } from '../clock';
import { visiblePlantSql } from '../policy';
import { leaseMs } from './delivery';

export const notificationAttemptLimit = 6;
export const completionEligible = `e.kind = 'completed' AND e.undone_at IS NULL AND m.removed_at IS NULL
  AND m.completion_notices_enabled AND m.id <> e.by_member AND ${visiblePlantSql('m.id')}`;
const targets = {
  daily: { table: 'daily_reminder_sends', key: 'local_date', cast: 'date', label: 'Daily',
    select: `SELECT m.id, $2::date FROM members m WHERE m.id = $1 AND m.reminders_enabled AND m.removed_at IS NULL` },
  weather: { table: 'weather_alert_sends', key: 'event_id', cast: 'uuid', label: 'Weather',
    select: `SELECT m.id, e.id FROM weather_events e JOIN members m ON m.household_id = e.household_id
      WHERE m.id = $1 AND e.id = $2 AND e.resolved_at IS NULL AND m.weather_alerts_enabled AND m.removed_at IS NULL AND m.role <> 'sitter'` },
  completion: { table: 'completion_notice_sends', key: 'event_id', cast: 'uuid', label: 'Completion',
    select: `SELECT m.id, e.id FROM completion_notice_sends q JOIN care_events e ON e.id = q.event_id
      JOIN members m ON m.id = q.member_id AND m.household_id = e.household_id
      JOIN plants p ON p.id = e.plant_id AND p.household_id = e.household_id
      WHERE m.id = $1 AND e.id = $2 AND ${completionEligible}` },
};
type Notification = { kind: keyof typeof targets; memberId: string; key: string };

// A single atomic claim owns retries, sent-state and stale-worker protection for every notice kind.
export async function withNotificationLease(notification: Notification, send: () => Promise<{ sent: number }>,
  database: Pick<pg.Pool, 'query'> = db(), clock: () => Date = now) {
  const { table, key, cast, select, label } = targets[notification.kind];
  let attempt = 0, finished = false;
  try {
    const at = clock();
    const claim = await database.query(`INSERT INTO ${table} AS n (member_id, ${key}, attempted_at, claimed_until)
      SELECT eligible.*, $3::timestamptz, $4::timestamptz FROM (${select}) eligible
      ON CONFLICT (member_id, ${key}) DO UPDATE SET attempted_at = $3,
        attempts = n.attempts + 1,
        claimed_until = $3::timestamptz + $5::double precision * interval '1 millisecond' * power(2, n.attempts)
      WHERE n.sent_at IS NULL AND n.claimed_until < $3 AND n.attempts < $6
      RETURNING claimed_until, attempts`,
    [notification.memberId, notification.key, at, new Date(at.getTime() + leaseMs), leaseMs, notificationAttemptLimit]);
    if (!claim.rowCount) return;
    const until = claim.rows[0].claimed_until;
    attempt = claim.rows[0].attempts;
    const { sent } = await send();
    // With no live devices, sent_at records a skip so a new device never replays the notice.
    const completion = await database.query(`UPDATE ${table} SET sent_at = $3 WHERE member_id = $1 AND ${key} = $2::${cast}
      AND claimed_until = $4 AND sent_at IS NULL
      AND ($5::boolean OR NOT EXISTS (SELECT 1 FROM push_subscriptions WHERE member_id = $1))`,
    [notification.memberId, notification.key, clock(), until, sent > 0]);
    finished = Boolean(completion.rowCount);
  } catch {
    if (attempt !== notificationAttemptLimit) console.error(`${label} push failed for one member. Will retry after its lease.`);
  } finally {
    if (attempt === notificationAttemptLimit && !finished) console.error(`${label} push gave up after ${notificationAttemptLimit} attempts.`);
  }
}

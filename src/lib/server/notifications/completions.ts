import { taskRules } from '$lib/schedule/rules';
import type { TaskType } from '$lib/schedule/engine';
import { db } from '../db';
import { now } from '../clock';
import { visiblePlantSql } from '../policy';
import { leaseMs, sendToMember } from './delivery';

const eligible = `e.kind = 'completed' AND e.undone_at IS NULL AND m.removed_at IS NULL
  AND m.completion_notices_enabled AND m.id <> e.by_member AND ${visiblePlantSql('m.id')}`;
export async function sendCompletionNotices() {
  const { rows } = await db().query(`SELECT e.id, m.id AS member_id, e.task_type, c.name AS custom_name,
    COALESCE(p.nickname, s.canonical_name) AS plant_name
    FROM completion_notice_sends n JOIN care_events e ON e.id = n.event_id
    JOIN members m ON m.id = n.member_id AND m.household_id = e.household_id
    JOIN plants p ON p.id = e.plant_id AND p.household_id = e.household_id
    JOIN species s ON s.gbif_key = p.gbif_key LEFT JOIN custom_tasks c ON c.id = e.custom_task_id
    WHERE ${eligible} AND EXISTS (SELECT 1 FROM push_subscriptions WHERE member_id = m.id)
    AND n.sent_at IS NULL AND n.claimed_until < $1 ORDER BY e.occurred_at, e.id, m.id`, [now()]);
  for (const row of rows) {
    try {
      const at = now(), until = new Date(at.getTime() + leaseMs);
      const claim = await db().query(`UPDATE completion_notice_sends n SET attempted_at = $3, claimed_until = $4
        FROM care_events e JOIN members m ON m.household_id = e.household_id
        JOIN plants p ON p.id = e.plant_id AND p.household_id = e.household_id
        WHERE n.event_id = e.id AND n.member_id = m.id AND e.id = $1 AND m.id = $2 AND ${eligible}
        AND n.sent_at IS NULL AND n.claimed_until < $3 RETURNING n.event_id`, [row.id, row.member_id, at, until]);
      if (!claim.rowCount) continue;
      const label = row.custom_name ?? taskRules[row.task_type as TaskType]?.label ?? 'Care';
      const { sent } = await sendToMember(row.member_id, { title: 'Fytorium', body: `${label.slice(0, 60)} completed · ${row.plant_name.slice(0, 80)}`, tag: 'fytorium-completion-' + row.id, url: '/' });
      await db().query(`UPDATE completion_notice_sends SET sent_at = $3 WHERE event_id = $1 AND member_id = $2
        AND claimed_until = $4 AND sent_at IS NULL
        AND ($5::boolean OR NOT EXISTS (SELECT 1 FROM push_subscriptions WHERE member_id = $2))`, [row.id, row.member_id, now(), until, sent > 0]);
    } catch { console.error('Completion push failed for one member. Will retry after its lease.'); }
  }
}

import { taskRules } from '$lib/schedule/rules';
import type { TaskType } from '$lib/schedule/engine';
import { db } from '../db';
import { now } from '../clock';
import { sendToMember } from './delivery';
import { completionEligible, notificationAttemptLimit, withNotificationLease } from './lease';

export async function sendCompletionNotices() {
  const { rows } = await db().query(`SELECT e.id, m.id AS member_id, e.task_type, c.name AS custom_name,
    COALESCE(p.nickname, s.canonical_name) AS plant_name
    FROM completion_notice_sends n JOIN care_events e ON e.id = n.event_id
    JOIN members m ON m.id = n.member_id AND m.household_id = e.household_id
    JOIN plants p ON p.id = e.plant_id AND p.household_id = e.household_id
    JOIN species s ON s.gbif_key = p.gbif_key LEFT JOIN custom_tasks c ON c.id = e.custom_task_id
    WHERE ${completionEligible} AND EXISTS (SELECT 1 FROM push_subscriptions WHERE member_id = m.id AND enabled)
    AND n.sent_at IS NULL AND n.claimed_until < $1 AND n.attempts < $2 ORDER BY e.occurred_at, e.id, m.id`, [now(), notificationAttemptLimit]);
  for (const row of rows) {
    const label = row.custom_name ?? taskRules[row.task_type as TaskType]?.label ?? 'Care';
    await withNotificationLease({ kind: 'completion', memberId: row.member_id, key: row.id }, () => sendToMember(row.member_id, {
      title: 'Fytorium', body: `${label.slice(0, 60)} completed · ${row.plant_name.slice(0, 80)}`,
      tag: 'fytorium-completion-' + row.id, url: '/',
    }));
  }
}

import type { Identity } from '../auth';
import { calendarDate, now } from '../clock';
import { config } from '../config';
import { db } from '../db';
import { listedTasks } from '../tasks';
import { sendToMember } from './delivery';

const leaseMs = 5 * 60 * 1000;

async function sendWeatherAlerts() {
  const { rows } = await db().query(`SELECT e.id, e.kind, m.id AS member_id,
    COALESCE(p.nickname, s.canonical_name) AS plant_name
    FROM weather_events e JOIN members m ON m.household_id = e.household_id
    LEFT JOIN plants p ON p.id = e.plant_id LEFT JOIN species s ON s.gbif_key = p.gbif_key
    WHERE e.resolved_at IS NULL AND m.weather_alerts_enabled
    AND EXISTS (SELECT 1 FROM push_subscriptions WHERE member_id = m.id)
    AND NOT EXISTS (SELECT 1 FROM weather_alert_sends WHERE event_id = e.id AND member_id = m.id
      AND (sent_at IS NOT NULL OR claimed_until >= $1))
    ORDER BY e.created_at, e.id, m.id`, [now()]);
  for (const row of rows) {
    try {
      const at = now(), until = new Date(at.getTime() + leaseMs);
      const claimed = await db().query(`INSERT INTO weather_alert_sends (event_id, member_id, attempted_at, claimed_until)
        SELECT e.id, m.id, $3, $4 FROM weather_events e JOIN members m ON m.household_id = e.household_id
        WHERE e.id = $1 AND m.id = $2 AND e.resolved_at IS NULL AND m.weather_alerts_enabled
        ON CONFLICT (event_id, member_id) DO UPDATE SET attempted_at = $3, claimed_until = $4
        WHERE weather_alert_sends.sent_at IS NULL AND weather_alert_sends.claimed_until < $3
        RETURNING event_id`, [row.id, row.member_id, at, until]);
      if (!claimed.rowCount) continue;
      const { sent } = await sendToMember(row.member_id, { title: 'Fytorium weather alert',
        body: row.kind === 'rain' ? 'Rain reported. Check outdoor watering on Today.' : `Low temperature forecast${row.plant_name ? ' · ' + row.plant_name.slice(0, 80) : ''}. Check Today.`,
        tag: 'fytorium-weather-' + row.id, url: '/' });
      // sent_at also marks a skip when every device expired, so new devices do not replay it.
      await db().query(`UPDATE weather_alert_sends SET sent_at = $3
        WHERE event_id = $1 AND member_id = $2 AND claimed_until = $4 AND sent_at IS NULL
        AND ($5::boolean OR NOT EXISTS (SELECT 1 FROM push_subscriptions WHERE member_id = $2))`,
      [row.id, row.member_id, now(), until, sent > 0]);
    } catch { console.error('Weather push failed for one member. Will retry after its lease.'); }
  }
}
export async function runPushWorkerOnce() {
  if (!config().vapidPublicKey) return;
  const at = now();
  const date = calendarDate(at);
  const localTime = new Intl.DateTimeFormat('en-GB', { timeZone: config().householdTimezone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(at);
  const { rows } = await db().query(`SELECT m.id, m.role, m.household_id, h.name FROM members m JOIN households h ON h.id = m.household_id
    WHERE m.reminders_enabled AND m.reminder_time <= $1
    AND EXISTS (SELECT 1 FROM push_subscriptions WHERE member_id = m.id)
    AND NOT EXISTS (SELECT 1 FROM daily_reminder_sends WHERE member_id = m.id AND local_date = $2::date
      AND (sent_at IS NOT NULL OR claimed_until >= $3)) ORDER BY m.id`, [localTime, date, at]);
  for (const member of rows) {
    try {
      const identity: Identity = { member: { id: member.id, role: member.role }, household: { id: member.household_id, name: member.name } };
      const today = await listedTasks(identity, 'today');
      const tasks = Object.values(today.groups).flat().filter(task => !task.completed_event && task.status === 'due' && task.dueOn <= today.today);
      const claimedAt = now(), until = new Date(claimedAt.getTime() + leaseMs);
      // Only this local day's summary may be claimed, never yesterday's missed reminder.
      if (!tasks.length || today.today !== date || calendarDate(claimedAt) !== date) continue;
      const claimed = await db().query(`INSERT INTO daily_reminder_sends (member_id, local_date, attempted_at, claimed_until)
        SELECT id, $2::date, $3, $4 FROM members WHERE id = $1 AND reminders_enabled
        ON CONFLICT (member_id, local_date) DO UPDATE SET attempted_at = $3, claimed_until = $4
        WHERE daily_reminder_sends.sent_at IS NULL AND daily_reminder_sends.claimed_until < $3
        RETURNING member_id`, [member.id, date, claimedAt, until]);
      if (!claimed.rowCount) continue;
      const late = tasks.filter(task => task.status === 'due' && task.lateDays > 0).length;
      const names = [...new Set(tasks.map(task => task.plant_name))].slice(0, 3).map(name => name.slice(0, 60));
      const { sent } = await sendToMember(member.id, { title: 'Fytorium', body: `${tasks.length} ${tasks.length === 1 ? 'task' : 'tasks'} today · ${late} late · ${names.join(', ')}`, tag: 'fytorium-daily', url: '/' });
      // No live devices left counts as skipped for the day, recorded in sent_at.
      await db().query(`UPDATE daily_reminder_sends SET sent_at = $3
        WHERE member_id = $1 AND local_date = $2::date AND claimed_until = $4 AND sent_at IS NULL
        AND ($5::boolean OR NOT EXISTS (SELECT 1 FROM push_subscriptions WHERE member_id = $1))`,
      [member.id, date, now(), until, sent > 0]);
    } catch { console.error('Daily push failed for one member. Will retry after its lease.'); }
  }
  await sendWeatherAlerts();
}
let started = false;
export function startPushWorker() {
  if (started || !config().pushWorkerEnabled || !config().vapidPublicKey) return;
  started = true;
  let running = false;
  setInterval(async () => {
    if (running) return;
    running = true;
    try { await runPushWorkerOnce(); }
    catch { console.error('Push worker could not finish its tick.'); }
    finally { running = false; }
  }, 30000).unref();
}

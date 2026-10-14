import type { Identity } from '../auth';
import { calendarDate, now } from '../clock';
import { fcmConfigured } from './fcm-config';
import { config } from '../config';
import { db } from '../db';
import { listedTasks } from '../tasks';
import { sendCompletionNotices } from './completions';
import { availableDeviceSql, sendToMember } from './delivery';
import { notificationAttemptLimit, withNotificationLease } from './lease';

async function sendWeatherAlerts() {
  const { rows } = await db().query(`SELECT e.id, e.kind, m.id AS member_id,
    COALESCE(p.nickname, s.canonical_name) AS plant_name
    FROM weather_events e JOIN members m ON m.household_id = e.household_id
    LEFT JOIN plants p ON p.id = e.plant_id LEFT JOIN species s ON s.gbif_key = p.gbif_key
    WHERE e.resolved_at IS NULL AND m.weather_alerts_enabled AND m.removed_at IS NULL AND m.role <> 'sitter'
    AND ${availableDeviceSql('m.id', '$1')}
    AND NOT EXISTS (SELECT 1 FROM weather_alert_sends WHERE event_id = e.id AND member_id = m.id
      AND (sent_at IS NOT NULL OR claimed_until >= $1 OR attempts >= $2))
    ORDER BY e.created_at, e.id, m.id`, [now(), notificationAttemptLimit]);
  for (const row of rows) {
    await withNotificationLease({ kind: 'weather', memberId: row.member_id, key: row.id }, () => sendToMember(row.member_id, {
      title: 'Fytorium weather alert',
      body: row.kind === 'rain' ? 'Rain reported. Check outdoor watering on Today.' : `Low temperature forecast${row.plant_name ? ' · ' + row.plant_name.slice(0, 80) : ''}. Check Today.`,
      tag: 'fytorium-weather-' + row.id, url: '/',
    }));
  }
}
export async function runPushWorkerOnce() {
  if (!config().vapidPublicKey && !fcmConfigured()) return;
  const at = now();
  const date = calendarDate(at);
  const localTime = new Intl.DateTimeFormat('en-GB', { timeZone: config().householdTimezone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(at);
  const { rows } = await db().query(`SELECT m.id, m.role, m.household_id, h.name FROM members m JOIN households h ON h.id = m.household_id
    WHERE m.removed_at IS NULL AND m.reminders_enabled AND m.reminder_time <= $1
    AND ${availableDeviceSql('m.id', '$3')}
    AND NOT EXISTS (SELECT 1 FROM daily_reminder_sends WHERE member_id = m.id AND local_date = $2::date
      AND (sent_at IS NOT NULL OR claimed_until >= $3 OR attempts >= $4)) ORDER BY m.id`, [localTime, date, at, notificationAttemptLimit]);
  for (const member of rows) {
    try {
      const identity: Identity = { member: { id: member.id, role: member.role }, household: { id: member.household_id, name: member.name } };
      const today = await listedTasks(identity, 'today');
      const tasks = Object.values(today.groups).flat().filter(task => !task.completed_event && task.status === 'due' && task.dueOn <= today.today);
      // Only this local day's summary may be claimed, never yesterday's missed reminder.
      if (!tasks.length || today.today !== date || calendarDate(now()) !== date) continue;
      const late = tasks.filter(task => task.status === 'due' && task.lateDays > 0).length;
      const names = [...new Set(tasks.map(task => task.plant_name))].slice(0, 3).map(name => name.slice(0, 60));
      await withNotificationLease({ kind: 'daily', memberId: member.id, key: date }, () => sendToMember(member.id, {
        title: 'Fytorium', body: `${tasks.length} ${tasks.length === 1 ? 'task' : 'tasks'} today · ${late} late · ${names.join(', ')}`,
        tag: 'fytorium-daily', url: '/',
      }));
    } catch { console.error('Daily push failed for one member. Will retry after its lease.'); }
  }
  await sendWeatherAlerts();
  await sendCompletionNotices();
}
let started = false;
export function startPushWorker() {
  if (started || !config().pushWorkerEnabled || !config().vapidPublicKey && !fcmConfigured()) return;
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

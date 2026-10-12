import type { ForecastDay, WeatherReport } from '$lib/weather';
import { db } from '../db';
import { calendarDate, now } from '../clock';
import { rainRule } from '$lib/schedule/rules';
import { recentRain } from './rain';

function scheduleSnapshot(days: ForecastDay[], fetchedAt: Date | null, today: string): WeatherReport['snapshot'] {
  const endOfWeek = new Date(today + 'T00:00:00Z'); endOfWeek.setUTCDate(endOfWeek.getUTCDate() + 6);
  const week = days.filter(day => day.date >= today && day.date <= endOfWeek.toISOString().slice(0, 10));
  const todayDay = days.find(day => day.date === today);
  return fetchedAt && (week.length === 7 || todayDay) ? {
    today, fetchedAt: fetchedAt.toISOString(),
    ...(week.length === 7 ? { meanMaxTemperatureC: week.reduce((sum, day) => sum + day.maxC, 0) / 7 } : {}),
    ...(todayDay ? { daylightHours: todayDay.daylightSeconds / 3600 } : {}),
  } : null;
}

// Task-only readers need forecast factors, not the private feed weather report.
export async function taskWeather(household: string): Promise<WeatherReport> {
  const { rows } = await db().query("SELECT data, fetched_at FROM weather_cache WHERE household_id = $1 AND kind = 'forecast'", [household]);
  return { locationSet: false, forecastFetchedAt: null, unavailableSince: null, current: null, alerts: [], events: [], lastRainBatch: null, rain: null,
    snapshot: scheduleSnapshot(rows[0]?.data ?? [], rows[0]?.fetched_at ?? null, calendarDate(now())) };
}

export async function weatherReport(household: string, plantId?: string): Promise<WeatherReport> {
  const [location, cache, events, count, batches, rainBatches] = await Promise.all([
    db().query('SELECT latitude FROM households WHERE id = $1', [household]),
    db().query('SELECT * FROM weather_cache WHERE household_id = $1', [household]),
    db().query("SELECT e.*, e.starts_on::text, e.ends_on::text, COALESCE(p.nickname, sp.canonical_name) AS plant_name FROM weather_events e LEFT JOIN plants p ON p.id = e.plant_id AND p.household_id = e.household_id LEFT JOIN species sp ON sp.gbif_key = p.gbif_key LEFT JOIN sites s ON s.id = p.site_id WHERE e.household_id = $1 AND e.resolved_at IS NULL AND ((e.kind = 'rain' AND e.plant_id IS NULL) OR (p.status = 'active' AND s.location = 'outdoor' AND e.ends_on >= $2::date)) AND ($3::uuid IS NULL OR p.id = $3) ORDER BY e.starts_on, e.id", [household, calendarDate(now()), plantId ?? null]),
    db().query("SELECT count(*)::integer AS n FROM plants p JOIN sites s ON s.id = p.site_id WHERE p.household_id = $1 AND p.status = 'active' AND s.location = 'outdoor'", [household]),
    db().query("SELECT b.request_id AS id, (SELECT count(*)::integer FROM care_events e WHERE e.batch_id = b.request_id AND e.household_id = $1) AS plant_count FROM care_actions b WHERE b.household_id = $1 AND b.action = 'rain' AND b.batch_id IS NULL AND b.undone_at IS NULL ORDER BY b.created_at DESC, b.request_id DESC LIMIT 1", [household]),
    db().query("SELECT e.starts_on::text, b.request_id AS id FROM weather_events e JOIN care_actions b ON b.request_id = e.batch_id WHERE e.household_id = $1 AND e.kind = 'rain' AND e.resolved_at IS NULL AND b.undone_at IS NULL ORDER BY e.starts_on DESC", [household]),
  ]);
  const forecast = cache.rows.find(row => row.kind === 'forecast');
  const days: ForecastDay[] = forecast?.data ?? [];
  const today = calendarDate(now());
  const recent = recentRain(days, today);
  const batchId = rainBatches.rows.find(row => recent.some(day => day.date === row.starts_on))?.id ?? null;
  const precipitationMm = Math.max(0, ...recent.map(day => day.precipitationMm));
  const todayDay = days.find(day => day.date === today);
  const snapshot = scheduleSnapshot(days, forecast?.fetched_at ?? null, today);
  const failures = cache.rows.map(row => row.unavailable_since as Date | null).filter((date): date is Date => !!date).sort((a, b) => a.getTime() - b.getTime());
  return { locationSet: location.rows[0].latitude !== null, forecastFetchedAt: forecast?.fetched_at?.toISOString() ?? null,
    unavailableSince: failures[0]?.toISOString() ?? null, snapshot, alerts: events.rows.filter(event => event.kind === 'low_temperature'),
    events: events.rows, current: todayDay && forecast?.fetched_at ? { date: today, minC: todayDay.minC, maxC: todayDay.maxC, fetchedAt: forecast.fetched_at.toISOString() } : null,
    lastRainBatch: batches.rows[0] ? { id: batches.rows[0].id, plantCount: batches.rows[0].plant_count } : null,
    rain: precipitationMm >= rainRule.thresholdMm && count.rows[0].n > 0 ? { plantCount: count.rows[0].n, precipitationMm, ruleId: rainRule.ruleId, batchId } : null };
}

import type { ForecastDay } from '$lib/weather';
import { rainRule } from '$lib/schedule/rules';
import { db } from '../db';
import { now, calendarDate } from '../clock';
import { effectiveCareProfiles } from '../care/catalogue';
import { applicableHardiness } from './hardiness';
import { recentRain } from './rain';

export async function reconcileWeatherEvents(household: string) {
  const client = await db().connect();
  try {
    await client.query('BEGIN');
    // Serialize event reconciliation with location changes, not external HTTP requests.
    await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
    const cache = await client.query("SELECT data FROM weather_cache WHERE household_id = $1 AND kind = 'forecast'", [household]);
    const days: ForecastDay[] = cache.rows[0]?.data ?? [];
    if (!days.length) { await client.query('COMMIT'); return; }
    const today = calendarDate(now());
    const forecast = days.filter(day => day.date >= today);
    const plants = await client.query("SELECT p.id, p.gbif_key, p.pot_type FROM plants p JOIN sites s ON s.id = p.site_id WHERE p.household_id = $1 AND p.status = 'active' AND s.location = 'outdoor'", [household]);
    const profiles = await effectiveCareProfiles(plants.rows.map(plant => plant.gbif_key), household);
    const open = await client.query('SELECT *, starts_on::text, ends_on::text FROM weather_events WHERE household_id = $1 AND resolved_at IS NULL', [household]);
    const retained = new Set<string>();
    // Resolve first so a newly joined spell can reuse one event without overlapping the others.
    await client.query("UPDATE weather_events SET resolved_at = $2 WHERE household_id = $1 AND kind = 'low_temperature' AND resolved_at IS NULL", [household, now()]);
    for (const plant of plants.rows) {
      const threshold = applicableHardiness(profiles.get(plant.gbif_key)!, plant.pot_type);
      const spells: ForecastDay[][] = [];
      let spell: ForecastDay[] = [];
      for (const day of forecast) {
        if (day.minC < threshold.thresholdC) { spell.push(day); }
        else if (spell.length) { spells.push(spell); spell = []; }
      }
      if (spell.length) spells.push(spell);
      for (const cold of spells) {
        const start = cold[0].date, end = cold.at(-1)!.date;
        const payload = { ...threshold, minC: Math.min(...cold.map(day => day.minC)) };
        const existing = open.rows.find(event => event.kind === 'low_temperature' && event.plant_id === plant.id && event.starts_on <= end && event.ends_on >= start && !retained.has(event.id));
        if (existing) {
          retained.add(existing.id);
          await client.query('UPDATE weather_events SET starts_on = LEAST(starts_on, $2::date), ends_on = $3, payload = $4, resolved_at = NULL WHERE id = $1', [existing.id, start, end, JSON.stringify(payload)]);
        } else {
          const inserted = await client.query("INSERT INTO weather_events (kind, household_id, plant_id, starts_on, ends_on, payload, created_at) VALUES ('low_temperature', $1, $2, $3, $4, $5, $6) ON CONFLICT DO NOTHING RETURNING id", [household, plant.id, start, end, JSON.stringify(payload), now()]);
          if (!inserted.rowCount) await client.query("UPDATE weather_events SET starts_on = LEAST(starts_on, $2::date), ends_on = GREATEST(ends_on, $3::date), payload = $4 WHERE plant_id = $1 AND kind = 'low_temperature' AND resolved_at IS NULL AND daterange(starts_on, ends_on, '[]') && daterange($2::date, $3::date, '[]')", [plant.id, start, end, JSON.stringify(payload)]);
        }
      }
    }
    for (const day of recentRain(days, today)) {
      const existing = open.rows.find(event => event.kind === 'rain' && event.starts_on === day.date);
      if (existing) retained.add(existing.id);
      else await client.query("INSERT INTO weather_events (kind, household_id, starts_on, ends_on, payload, created_at) VALUES ('rain', $1, $2, $2, $3, $4) ON CONFLICT DO NOTHING", [household, day.date, JSON.stringify({ precipitationMm: day.precipitationMm, ruleId: rainRule.ruleId }), now()]);
    }
    for (const event of open.rows) if (!retained.has(event.id)) await client.query('UPDATE weather_events SET resolved_at = $2 WHERE id = $1', [event.id, now()]);
    await client.query('COMMIT');
  } catch (cause) { await client.query('ROLLBACK'); throw cause; }
  finally { client.release(); }
}

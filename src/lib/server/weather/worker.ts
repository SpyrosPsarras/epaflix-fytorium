import { db, withTransaction } from '../db';
import { calendarDate, now } from '../clock';
import { effectiveCareProfiles } from '../care/catalogue';
import { config } from '../config';
import { reconcileWeatherEvents } from './events';
import { fetchClimate, fetchForecast } from './open-meteo';

async function refresh(household: string, kind: 'forecast' | 'climate') {
  const time = now();
  const location = await withTransaction(async (client) => {
    const row = await client.query('SELECT * FROM households WHERE id = $1 AND latitude IS NOT NULL FOR UPDATE', [household]);
    let location = row.rows[0];
    if (location) {
      await client.query('INSERT INTO weather_cache (household_id, kind, location_revision) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING', [household, kind, location.location_revision]);
      const claim = await client.query("UPDATE weather_cache SET attempted_at = $3 WHERE household_id = $1 AND kind = $2 AND (attempted_at IS NULL OR attempted_at <= $3::timestamptz - CASE WHEN kind = 'forecast' THEN interval '1 hour' WHEN unavailable_since IS NOT NULL THEN interval '6 hours' ELSE interval '1 month' END) RETURNING kind", [household, kind, time]);
      if (!claim.rowCount) location = undefined;
    }
    return location;
  });
  if (!location) return false;
  try {
    const coordinates = { latitude: location.latitude, longitude: location.longitude, label: null };
    const data = kind === 'forecast' ? await fetchForecast(coordinates) : await fetchClimate(coordinates);
    // Revision and attempt fence a slow response after a location change or later worker claim.
    const stored = await db().query('UPDATE weather_cache SET data = $4, fetched_at = $5, unavailable_since = NULL WHERE household_id = $1 AND kind = $2 AND location_revision = $3 AND attempted_at = $5', [household, kind, location.location_revision, JSON.stringify(data), time]);
    return !!stored.rowCount;
  } catch {
    await db().query('UPDATE weather_cache SET unavailable_since = COALESCE(unavailable_since, $4) WHERE household_id = $1 AND kind = $2 AND location_revision = $3 AND attempted_at = $4', [household, kind, location.location_revision, time]);
  }
  return false;
}
const reconciledInputs = new Map<string, string>();
export async function runWeatherWorkerOnce() {
  const households = await db().query('SELECT id FROM households WHERE latitude IS NOT NULL');
  for (const { id } of households.rows) {
    const forecastStored = await refresh(id, 'forecast');
    await refresh(id, 'climate');
    const plants = await db().query("SELECT p.id, p.gbif_key, p.pot_type, p.status, s.location FROM plants p LEFT JOIN sites s ON s.id = p.site_id WHERE p.household_id = $1 ORDER BY p.id", [id]);
    const profiles = await effectiveCareProfiles(plants.rows.map(plant => plant.gbif_key), id);
    const inputs = JSON.stringify([calendarDate(now()), plants.rows, [...profiles]]);
    if (forecastStored || reconciledInputs.get(id) !== inputs) {
      reconciledInputs.delete(id);
      await reconcileWeatherEvents(id);
      reconciledInputs.set(id, inputs);
    }
  }
}
let started = false;
export function startWeatherWorker() {
  if (started || !config().weatherWorkerEnabled) return;
  started = true;
  let running = false;
  setInterval(async () => {
    if (running) return;
    running = true;
    try { await runWeatherWorkerOnce(); }
    catch { console.error('Weather worker could not finish its tick. It will retry.'); }
    finally { running = false; }
  }, 5000).unref();
}

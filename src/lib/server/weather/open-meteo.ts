import type { ForecastDay, HouseholdLocation } from '$lib/weather';
import { config } from '../config';

type Daily = { time: string[]; [field: string]: (number | string)[] };
async function dailyRequest(url: URL, fields: string[]): Promise<Daily> {
  // URLs contain personal coordinates. Do not log exceptions or retain the raw response.
  const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
  if (!response.ok) throw new Error('Weather unavailable');
  const payload = await response.json();
  const daily = payload?.daily;
  if (!daily || !Array.isArray(daily.time) || !daily.time.length || !daily.time.every((date: unknown) => typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date) && new Date(date).toISOString().slice(0, 10) === date)) throw new Error('Invalid weather dates');
  if (new Set(daily.time).size !== daily.time.length) throw new Error('Duplicate weather dates');
  for (const field of fields) if (!Array.isArray(daily[field]) || daily[field].length !== daily.time.length || !daily[field].every((value: unknown) => typeof value === 'number' && Number.isFinite(value))) throw new Error('Invalid weather array');
  return daily;
}
function locationUrl(path: string, base: URL, location: HouseholdLocation) {
  const url = new URL(path, base);
  url.searchParams.set('latitude', String(location.latitude));
  url.searchParams.set('longitude', String(location.longitude));
  return url;
}
export async function fetchForecast(location: HouseholdLocation): Promise<ForecastDay[]> {
  const fields = ['temperature_2m_min', 'temperature_2m_max', 'precipitation_sum', 'daylight_duration'];
  const url = locationUrl('v1/forecast', config().openMeteoBaseUrl, location);
  url.searchParams.set('daily', fields.join(','));
  url.searchParams.set('past_days', '2');
  url.searchParams.set('forecast_days', '7');
  url.searchParams.set('timezone', config().householdTimezone);
  const daily = await dailyRequest(url, fields);
  return daily.time.map((date, i) => {
    const minC = Number(daily.temperature_2m_min[i]), maxC = Number(daily.temperature_2m_max[i]);
    const precipitationMm = Number(daily.precipitation_sum[i]), daylightSeconds = Number(daily.daylight_duration[i]);
    if (minC > maxC || precipitationMm < 0 || daylightSeconds < 0 || daylightSeconds > 86400) throw new Error('Invalid forecast');
    return { date, minC, maxC, precipitationMm, daylightSeconds };
  }).sort((a, b) => a.date.localeCompare(b.date));
}
export async function fetchClimate(location: HouseholdLocation): Promise<number[]> {
  const url = locationUrl('v1/climate', config().openMeteoClimateBaseUrl, location);
  url.searchParams.set('start_date', '1991-01-01');
  url.searchParams.set('end_date', '2020-12-31');
  url.searchParams.set('models', 'CMCC_CM2_VHR4');
  url.searchParams.set('daily', 'temperature_2m_min');
  const daily = await dailyRequest(url, ['temperature_2m_min']);
  const months = Array.from({ length: 12 }, () => [] as number[]);
  daily.time.forEach((date, i) => months[Number(date.slice(5, 7)) - 1].push(Number(daily.temperature_2m_min[i])));
  if (months.some(month => !month.length)) throw new Error('Incomplete climate year');
  return months.map(month => month.reduce((sum, value) => sum + value, 0) / month.length);
}

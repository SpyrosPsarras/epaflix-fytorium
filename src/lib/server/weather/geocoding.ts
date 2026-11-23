import { error } from '@sveltejs/kit';
import { config } from '../config';

export type PlaceResult = { label: string; name: string; region: string | null; country: string | null; latitude: number; longitude: number };
const unavailable = "Place search isn't responding. Try again in a minute.";
const text = (value: unknown) => (typeof value === 'string' && value.trim() ? value.trim() : null);

// The query names a town the owner typed; it is not stored, cached or logged.
export async function searchPlaces(query: string, language: string): Promise<PlaceResult[]> {
  const url = new URL('v1/search', config().openMeteoGeocodingBaseUrl);
  url.search = new URLSearchParams({ name: query, count: '10', language, format: 'json' }).toString();
  let payload;
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error();
    payload = await response.json();
  } catch { error(503, unavailable); }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) error(503, unavailable);
  if (payload.results === undefined) return [];
  if (!Array.isArray(payload.results)) error(503, unavailable);
  // A row without a name or with coordinates out of range means the answer is not what Open-Meteo documents.
  return payload.results.slice(0, 10).map((row: Record<string, unknown>) => {
    const name = text(row?.name);
    const { latitude, longitude } = row ?? {};
    if (!name || typeof latitude !== 'number' || typeof longitude !== 'number' || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) error(503, unavailable);
    const region = text(row.admin1);
    const country = text(row.country);
    const label = [name, region !== name ? region : null, country].filter(Boolean).join(', ').slice(0, 200);
    return { label, name, region, country, latitude, longitude };
  });
}

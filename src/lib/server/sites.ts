import { error, json } from '@sveltejs/kit';
import { locations, lightLevels, humidityLevels, type SiteInput } from '$lib/sites';

export function checkOrigin(request: Request) {
  const origin = request.headers.get('origin');
  if (origin && origin !== new URL(request.url).origin) error(403, 'Cross-origin request refused');
}

export async function readSite(request: Request): Promise<SiteInput | Response> {
  let input: unknown;
  try {
    if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') throw new Error('Expected JSON');
    input = await request.json();
  } catch {
    return json({ errors: { _form: 'Send a valid JSON object.' } }, { status: 400 });
  }
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    return json({ errors: { _form: 'Send a valid JSON object.' } }, { status: 400 });
  }
  const site = input as Record<string, unknown>;
  const errors: Record<string, string> = {};
  if (typeof site.name !== 'string' || !site.name.trim()) errors.name = 'Enter a site name.';
  else site.name = site.name.trim();
  for (const [field, values] of [['location', locations], ['light', lightLevels], ['humidity', humidityLevels]] as const) {
    if (!values.some(value => value === site[field])) errors[field] = `Choose one of: ${values.join(', ')}.`;
  }
  for (const field of ['grow_light', 'draft']) {
    if (typeof site[field] !== 'boolean') errors[field] = 'Choose yes or no.';
  }
  for (const period of ['warm', 'cold']) {
    const min = `${period}_min_c`;
    const max = `${period}_max_c`;
    for (const field of [min, max]) {
      site[field] ??= null;
      const value = site[field];
      if (value !== null && (typeof value !== 'number' || !Number.isInteger(value) || value < -2147483648 || value > 2147483647)) {
        errors[field] = 'Enter a whole number of °C or leave blank.';
      }
    }
    if (typeof site[min] === 'number' && typeof site[max] === 'number' && site[min] > site[max]) {
      errors[min] = 'Minimum must not exceed maximum.';
    }
  }
  if (Object.keys(errors).length) return json({ errors }, { status: 422 });
  return site as SiteInput;
}

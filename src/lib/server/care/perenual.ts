import { validCareValue, type CareFields } from '../../care';
import type { CareSource, CareResult } from './source';

type ObjectValue = Record<string, unknown>;
const object = (value: unknown): ObjectValue => value && typeof value === 'object' && !Array.isArray(value) ? value as ObjectValue : {};
export function mapPerenual(payload: unknown): Partial<CareFields> {
  const data = object(payload);
  const mapped: Record<string, unknown> = {};
  const benchmark = object(data.watering_general_benchmark);
  const unquote = (value: unknown) => String(value ?? '').trim().replace(/^(["'])(.*)\1$/, '$2').trim();
  const parts = unquote(benchmark.value).match(/^\s*(\d+(?:\.\d+)?)\s*(?:[-–]\s*(\d+(?:\.\d+)?))?\s*$/);
  const units: Record<string, number> = { day: 1, days: 1, week: 7, weeks: 7, hour: 1 / 24, hours: 1 / 24, month: 30, months: 30 };
  const unit = units[unquote(benchmark.unit).toLowerCase()];
  // A range uses its midpoint; month benchmarks use 30-day months, not calendar months.
  if (parts && unit && Number(parts[1]) <= Number(parts[2] ?? parts[1])) mapped.wateringIntervalDays = (Number(parts[1]) + Number(parts[2] ?? parts[1])) / 2 * unit;
  const sunlight: Record<string, CareFields['light']> = { full_shade: 'full_shade', part_shade: 'part_shade', sun_part_shade: 'part_sun', part_sun: 'part_sun', full_sun: 'full_sun' };
  if (Array.isArray(data.sunlight)) {
    // Multiple preferences select the least intense recognised tier.
    const tiers = data.sunlight.map(v => sunlight[String(v).trim().toLowerCase().replaceAll(/[ -]/g, '_')]).filter(Boolean);
    mapped.light = ['full_shade', 'part_shade', 'part_sun', 'full_sun'].find(tier => tiers.includes(tier as CareFields['light']));
  }
  const months = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];
  if (Array.isArray(data.pruning_month)) mapped.pruningMonths = [...new Set(data.pruning_month.map(v => months.indexOf(String(v).trim().toLowerCase()) + 1).filter(v => v > 0))];
  const hardiness = object(data.hardiness);
  if (hardiness.min !== '' && hardiness.max !== '' && hardiness.min != null && hardiness.max != null) mapped.hardiness = { min: Number(hardiness.min), max: Number(hardiness.max) };
  mapped.poisonousToPets = data.poisonous_to_pets;
  mapped.poisonousToHumans = data.poisonous_to_humans;
  mapped.indoor = data.indoor;
  if (typeof data.cycle === 'string') mapped.cycle = data.cycle.toLowerCase();
  const dimensions = object(data.dimensions);
  const sizeUnits: Record<string, number> = { cm: 1, centimeters: 1, m: 100, meters: 100, feet: 30.48, ft: 30.48, inches: 2.54, inch: 2.54 };
  const sizeUnit = sizeUnits[String(dimensions.unit).toLowerCase()];
  if (sizeUnit && dimensions.min_value != null && dimensions.max_value != null && dimensions.min_value !== '' && dimensions.max_value !== '') mapped.dimensions = { min: Number(dimensions.min_value) * sizeUnit, max: Number(dimensions.max_value) * sizeUnit, unit: 'cm' };
  if (Array.isArray(data.pest_susceptibility) && data.pest_susceptibility.length) mapped.pests = data.pest_susceptibility;
  return Object.fromEntries(Object.entries(mapped).filter(([field, value]) => validCareValue(field, value)));
}

type RequestResult = { body: unknown } | { retryLater: Date; quota?: boolean } | 'not_available' | 'auth_failed';
export function perenualSource(options: {
  baseUrl: URL; apiKey: string; now: () => Date;
  reserve: () => Promise<Date | null>;
  backoff: (until: Date) => Promise<void>;
}): CareSource {
  async function request(path: string, query?: string): Promise<RequestResult> {
    const wait = await options.reserve();
    if (wait) return { retryLater: wait, quota: true };
    const url = new URL(path, options.baseUrl);
    url.searchParams.set('key', options.apiKey);
    if (query) url.searchParams.set('q', query);
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
      if (response.status === 429) {
        const retry = response.headers.get('retry-after');
        const milliseconds = retry && /^\d+$/.test(retry) ? Number(retry) * 1000 : retry ? Date.parse(retry) - options.now().getTime() : 60000;
        const until = new Date(options.now().getTime() + Math.max(60000, Number.isFinite(milliseconds) ? milliseconds : 60000));
        await options.backoff(until);
        return { retryLater: until };
      }
      if ([401, 403].includes(response.status)) return 'auth_failed';
      if (response.status === 404) return 'not_available';
      if (!response.ok) return { retryLater: new Date(options.now().getTime() + 60000) };
      return { body: await response.json() };
    } catch {
      // Fetch errors can include URLs with keys. Never log or return the exception.
      return { retryLater: new Date(options.now().getTime() + 60000) };
    }
  }
  return {
    configured: Boolean(options.apiKey),
    async fetch(species): Promise<CareResult> {
      let key = species.sourceKey;
      if (!key) {
        const list = await request('api/v2/species-list', species.canonical_name);
        if (typeof list === 'string' || 'retryLater' in list) return list;
        const data = object(list.body).data;
        const match = Array.isArray(data) ? data.map(object).find(row => Array.isArray(row.scientific_name) && row.scientific_name.some(name => typeof name === 'string' && name.trim().toLowerCase() === species.canonical_name.trim().toLowerCase())) : undefined;
        if (!match || !Number.isInteger(match.id) || Number(match.id) <= 0) return 'not_available';
        key = String(match.id);
      }
      // The Personal plan documents details for IDs 1–3000 only.
      if (Number(key) > 3000) return 'not_available';
      const detail = await request('api/v2/species/details/' + key);
      if (typeof detail === 'string') return detail;
      if ('retryLater' in detail) return { ...detail, key };
      return { fields: mapPerenual(detail.body), fetchedAt: options.now(), key };
    },
  };
}

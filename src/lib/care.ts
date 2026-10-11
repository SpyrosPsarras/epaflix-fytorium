import type { CareField } from './schedule/engine';
import type { SiteInput } from './sites';

export type CareFields = {
  wateringIntervalDays: number;
  fertilizingIntervalDays: number;
  light: SiteInput['light'];
  humidityNeed: 'low' | 'medium' | 'high';
  temperatureRangeC: { min: number; max: number };
  hardiness: { min: number; max: number };
  poisonousToPets: boolean;
  poisonousToHumans: boolean;
  cycle: 'annual' | 'biennial' | 'perennial';
  dimensions: { min: number; max: number; unit: 'cm' };
  pruningMonths: number[];
  pests: string[];
  indoor: boolean;
};
export type CareName = keyof CareFields;
export type CareProfile = Record<CareName, CareField & { fetchedAt: string | null }>;
export const careLabels: Record<CareName, string> = {
  wateringIntervalDays: 'Watering interval in days', fertilizingIntervalDays: 'Fertilizing interval in days',
  light: 'Light', humidityNeed: 'Humidity need', temperatureRangeC: 'Temperature range in °C',
  hardiness: 'Hardiness zones', poisonousToPets: 'Poisonous to pets', poisonousToHumans: 'Poisonous to humans',
  cycle: 'Lifecycle', dimensions: 'Size in cm', pruningMonths: 'Pruning months', pests: 'Common pests', indoor: 'Indoor',
};
export const careNames = Object.keys(careLabels) as CareName[];
export function validCareValue(field: string, value: unknown): boolean {
  const number = (v: unknown, min: number, max: number) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
  const range = (min: number, max: number) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const v = value as Record<string, unknown>;
    return number(v.min, min, max) && number(v.max, min, max) && Number(v.min) <= Number(v.max)
      && Object.keys(v).every(key => ['min', 'max', ...(field === 'dimensions' ? ['unit'] : [])].includes(key));
  };
  switch (field) {
    case 'wateringIntervalDays': return number(value, .1, 365);
    case 'fertilizingIntervalDays': return number(value, 1, 730);
    case 'light': return ['full_shade', 'part_shade', 'part_sun', 'full_sun'].includes(String(value)) && typeof value === 'string';
    case 'humidityNeed': return ['low', 'medium', 'high'].includes(String(value)) && typeof value === 'string';
    case 'temperatureRangeC': return range(-60, 60);
    case 'hardiness': return range(1, 13);
    case 'dimensions': return range(0, 100000) && (value as { unit?: string }).unit === 'cm';
    case 'poisonousToPets': case 'poisonousToHumans': case 'indoor': return typeof value === 'boolean';
    case 'cycle': return typeof value === 'string' && ['annual', 'biennial', 'perennial'].includes(value);
    case 'pruningMonths': return Array.isArray(value) && value.length > 0 && value.length <= 12 && new Set(value).size === value.length && value.every(v => Number.isInteger(v) && v >= 1 && v <= 12);
    case 'pests': return Array.isArray(value) && value.length <= 30 && value.every(v => typeof v === 'string' && v.trim().length > 0 && v.length <= 100);
    default: return false;
  }
}

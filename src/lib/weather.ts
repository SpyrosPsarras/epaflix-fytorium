import type { CareField } from './schedule/engine';
export type HouseholdLocation = { latitude: number; longitude: number; label: string | null };
export type ForecastDay = { date: string; minC: number; maxC: number; precipitationMm: number; daylightSeconds: number };
export type WeatherEvent = { id: string; kind: 'low_temperature' | 'rain'; plant_id: string | null; plant_name: string | null; starts_on: string; ends_on: string; payload: { minC?: number; thresholdC?: number; hardiness?: CareField; potRuleId?: string; ruleId?: string; precipitationMm?: number } };
export type WeatherReport = {
  locationSet: boolean;
  forecastFetchedAt: string | null;
  unavailableSince: string | null;
  snapshot: { today: string; meanMaxTemperatureC?: number; daylightHours?: number; fetchedAt: string } | null;
  alerts: WeatherEvent[];
  events: WeatherEvent[];
  current: { date: string; minC: number; maxC: number; fetchedAt: string } | null;
  rain: { plantCount: number; precipitationMm: number; ruleId: string; batchId: string | null } | null;
  lastRainBatch: { id: string; plantCount: number } | null;
};
export type OutdoorMonths = { locationSet: boolean; pot: number[] | null; ground: number[] | null; hardiness: CareField; potThresholdC: number; groundThresholdC: number; marginC: number; ruleId: string; potRuleId: string; fetchedAt: string | null; unavailableSince: string | null };

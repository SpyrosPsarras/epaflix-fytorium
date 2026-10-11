import type { ForecastDay } from '$lib/weather';
import { rainRule } from '$lib/schedule/rules';

export function recentRain(days: ForecastDay[], today: string): ForecastDay[] {
  const yesterday = new Date(today + 'T00:00:00Z');
  yesterday.setUTCDate(yesterday.getUTCDate() - 1);
  return days.filter(day => (day.date === today || day.date === yesterday.toISOString().slice(0, 10)) && day.precipitationMm >= rainRule.thresholdMm);
}

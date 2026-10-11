import type { PlantWarning } from './warnings';
import type { WeatherEvent, WeatherReport } from './weather';

export const feedKinds = ['late_tasks', 'current_weather', 'weather_alerts', 'plant_warnings'] as const;
export type FeedSettings = Record<typeof feedKinds[number], boolean>;
export const feedLabels: Record<typeof feedKinds[number], string> = {
  late_tasks: 'Late tasks', current_weather: 'Current weather', weather_alerts: 'Weather alerts', plant_warnings: 'Plant warnings',
};
export type FeedCard = { id: string; href: string } & (
  { kind: 'late_tasks'; count: number; plants: { id: string; name: string }[] }
  | { kind: 'current_weather'; current: WeatherReport['current']; unavailableSince: string | null }
  | { kind: 'weather_alerts'; event: WeatherEvent }
  | { kind: 'plant_warnings'; plantId: string; plantName: string; warnings: PlantWarning[] }
);

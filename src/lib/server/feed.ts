import type { Identity } from './auth';
import type { FeedCard } from '$lib/feed';
import { plantWarnings } from '$lib/warnings';
import { listedTaskContext } from './tasks';
import { feedSettings } from './feed-settings';

export async function homeFeed(identity: Identity, batch?: Awaited<ReturnType<typeof listedTaskContext>>) {
  const [context, settings] = await Promise.all([batch ?? listedTaskContext(identity, 'today', true), feedSettings(identity)]);
  const { tasks, plants, sites, profiles, weather } = context;
  const cards: FeedCard[] = [];
  const late = Object.values(tasks.groups).flat().filter(task => task.status === 'due' && task.lateDays > 0);
  const latePlants = [...new Map(late.map(task => [task.plant_id, { id: task.plant_id, name: task.plant_name }])).values()];
  if (late.length) cards.push({ id: 'late_tasks', kind: 'late_tasks', href: latePlants.length === 1 ? '/plants/' + latePlants[0].id : '/#today', count: late.length,
    plants: latePlants });
  if (identity.member.role === 'sitter') return { cards: cards.filter(card => settings[card.kind]), settings, weather };
  if (weather.locationSet) cards.push({ id: 'current_weather', kind: 'current_weather', href: '/settings#weather-location-title',
    current: weather.current, unavailableSince: weather.unavailableSince });
  const hasOutdoorPlants = plants.some(plant => sites.get(plant.site_id)?.location === 'outdoor');
  const events = weather.events.filter(event => event.kind !== 'rain' || hasOutdoorPlants);
  if (hasOutdoorPlants && weather.lastRainBatch && !events.some(event => event.kind === 'rain')) events.push({
    id: 'rain_batch', kind: 'rain', plant_id: null, plant_name: null, starts_on: tasks.today, ends_on: tasks.today, payload: {},
  });
  for (const event of events) cards.push({ id: event.id, kind: 'weather_alerts',
    href: event.plant_id ? '/plants/' + event.plant_id : '/settings#weather-location-title', event });
  for (const plant of plants) {
    const warnings = plantWarnings({ careProfile: profiles.get(plant.gbif_key)!, site: sites.get(plant.site_id) ?? {},
      pot: plant, plant, locationSet: weather.locationSet, status: plant.status });
    if (warnings.length) cards.push({ id: plant.id, kind: 'plant_warnings', href: '/plants/' + plant.id, plantId: plant.id, plantName: plant.display_name, warnings });
  }
  return { cards: cards.filter(card => settings[card.kind]), settings, weather };
}

import type { CareField, ScheduleInput, TaskType } from './engine';

export type FactorInput = { [Group in 'site' | 'pot' | 'plant']: { group: Group; field: keyof ScheduleInput[Group] } }['site' | 'pot' | 'plant'];
export type FactorRule = { input: FactorInput; ruleId: string; factors: Readonly<Record<string, number>>; reason: string; bands?: readonly [number, string][]; neutralWhen?: (input: ScheduleInput) => boolean; neutralReason?: string };
export type TaskRules = { label: string; action: string; profileField: string; defaultInterval: CareField & { reason: string }; minDays: number; maxDays: number; factors: readonly FactorRule[] };
export const taskRules: Record<TaskType, TaskRules> = {
  water: {
    label: 'Water', action: 'watering', profileField: 'wateringIntervalDays', minDays: 1, maxDays: 60,
    defaultInterval: { value: 7, source: 'rule', ruleId: 'water.default@1',
      reason: 'Weekly watering is the starting estimate when species data is missing.' },
    factors: [
      { input: { group: 'site', field: 'light' }, ruleId: 'water.light@1', factors: { full_shade: 1.4, part_shade: 1.2, part_sun: 1, full_sun: .7 }, reason: 'More sunlight increases evaporation and water use.' },
      { input: { group: 'site', field: 'humidity' }, ruleId: 'water.humidity@1', factors: { dry: .75, normal: 1, humid: 1.25 }, reason: 'Dry air speeds transpiration; humid air slows it.' },
      { input: { group: 'site', field: 'location' }, ruleId: 'water.location@1', factors: { indoor: 1, outdoor: .8 }, reason: 'Outdoor exposure increases evaporation before weather data is available.' },
      { input: { group: 'site', field: 'grow_light' }, ruleId: 'water.site-grow-light@1', factors: { true: .8, false: 1 }, reason: 'A grow light increases water use.' },
      { input: { group: 'site', field: 'draft' }, ruleId: 'water.draft@1', factors: { true: .85, false: 1 }, reason: 'Moving air dries soil and foliage faster.' },
      { input: { group: 'pot', field: 'pot_type' }, ruleId: 'water.pot-type@1', factors: { plastic: 1, terracotta: .75, ceramic: 1, glass: 1, metal: 1, wood: .85, fabric: .7, in_ground: 1.4 }, reason: 'Porous pots dry faster; ground soil has a larger moisture reserve.' },
      { input: { group: 'pot', field: 'pot_size_cm' }, ruleId: 'water.pot-size@1', bands: [[12, 'small'], [25, 'medium'], [Infinity, 'large']], factors: { small: .75, medium: 1, large: 1.3 }, reason: 'A larger pot holds more soil and water.' },
      { input: { group: 'pot', field: 'drainage' }, ruleId: 'water.drainage@1', factors: { true: 1, false: 1.3 }, reason: 'Without drainage, retained water takes longer to clear.' },
      { input: { group: 'plant', field: 'plant_size' }, ruleId: 'water.plant-size@1', factors: { small: 1.1, medium: 1, large: .85, very_large: .7 }, reason: 'More foliage uses more water for the same pot.' },
      { input: { group: 'plant', field: 'growth_stage' }, ruleId: 'water.growth-stage@1', factors: { seedling: .7, young: .9, mature: 1 }, reason: 'Developing roots need smaller, more frequent waterings.' },
      { input: { group: 'plant', field: 'window_distance_cm' }, ruleId: 'water.window-distance@1', bands: [[50, 'close'], [200, 'middle'], [Infinity, 'far']], factors: { close: .85, middle: 1, far: 1.25 }, reason: 'Distance from a window reduces light and water use.' },
      { input: { group: 'plant', field: 'grow_light' }, ruleId: 'water.plant-grow-light@1', factors: { true: .8, false: 1 }, reason: 'A grow light increases water use, counted once if inherited from the site.',
        neutralWhen: input => input.plant.grow_light === true && input.site.grow_light === true,
        neutralReason: 'Already counted in site.grow_light; no further adjustment.' },
      { input: { group: 'plant', field: 'near_ac' }, ruleId: 'water.near-ac@1', factors: { true: .85, false: 1 }, reason: 'Air conditioning dries the air around the plant.' },
      { input: { group: 'plant', field: 'near_heater' }, ruleId: 'water.near-heater@1', factors: { true: .7, false: 1 }, reason: 'Heater warmth increases evaporation.' },
    ],
  },
};

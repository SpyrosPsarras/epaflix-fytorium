import type { BasedOn, CareField, ScheduleInput } from './engine';

export const taskTypes = ['water', 'fertilize', 'mist', 'clean_leaves', 'remove_dead', 'cut_back', 'repot'] as const;
export type TaskType = typeof taskTypes[number];
export type FactorInput = { [Group in 'site' | 'pot' | 'plant']: { group: Group; field: keyof ScheduleInput[Group] } }['site' | 'pot' | 'plant'];
type FactorKeys<T> = T extends boolean ? 'true' | 'false' : T extends string ? T : never;
type FactorDescription = { ruleId: string; reason: string; neutralWhen?: (input: ScheduleInput) => boolean; neutralReason?: string };
// Enum keys follow the selected input field; numerical fields use explicitly named bands.
export type FactorRule = { [G in 'site' | 'pot' | 'plant']: {
  [F in keyof ScheduleInput[G]]-?: FactorDescription & { input: { group: G; field: F } } &
    (NonNullable<ScheduleInput[G][F]> extends number
      ? { bands: readonly [number, string][]; factors: Readonly<Record<string, number>> }
      : { bands?: never; factors: Readonly<Record<FactorKeys<NonNullable<ScheduleInput[G][F]>>, number>> })
}[keyof ScheduleInput[G]] }['site' | 'pot' | 'plant'];
type IntervalRule = CareField & { value: number; reason: string };
const intervalRule = (value: number, ruleId: string, reason: string): IntervalRule => ({ value, source: 'rule', ruleId, reason });
type Anchor = 'added_date' | 'added_plus_interval' | ((input: ScheduleInput, completion: string | undefined) => { dueOn: string; basedOn: BasedOn[] });
// Northern hemisphere: slower winter growth and evaporation, neutral May–September.
export const wateringMonthFactors = [1.5, 1.5, 1.25, 1.1, 1, 1, 1, 1, 1, 1.1, 1.25, 1.5] as const;
export type TaskRules = {
  label: string; action: string; profileField?: string;
  defaultInterval: IntervalRule | ((input: ScheduleInput, intervals: (type: TaskType) => number) => IntervalRule);
  minDays: number; maxDays: number; factors: readonly FactorRule[]; monthFactors?: { values: readonly number[]; ruleId: string; reason: string };
  anchor: Anchor; season?: { firstMonth: number; lastMonth: number; ruleId: string };
  applicability?: (input: ScheduleInput) => { applicable: boolean; basedOn: BasedOn[] };
};

function pruningAnchor(input: ScheduleInput, completion: string | undefined) {
  const supplied = input.careProfile.pruningMonths;
  const months = Array.isArray(supplied?.value) ? supplied.value.filter((month): month is number => Number.isInteger(month) && month >= 1 && month <= 12).sort((a, b) => a - b) : [];
  const effective = months.length ? months : [3];
  const year = Number((completion ?? input.addedOn).slice(0, 4)) + (completion ? 1 : 0);
  const candidates = effective.map(month => `${year}-${String(month).padStart(2, '0')}-01`);
  const dueOn = completion ? candidates[0] : candidates.find(date => date >= input.addedOn) ?? `${year + 1}-${String(effective[0]).padStart(2, '0')}-01`;
  return { dueOn, basedOn: [{ input: 'pruningMonths', value: effective, source: months.length ? supplied.source : 'rule',
    ruleId: months.length ? supplied.ruleId : 'cut_back.pruning_months@1', adjustment: 'First pruning month on or after addition; after completion, first pruning month of the next year.' } satisfies BasedOn] };
}

function mistApplicability(input: ScheduleInput) {
  const need = input.careProfile.humidityNeed ?? { value: 'medium', source: 'rule', ruleId: 'mist.humidity_need@1' };
  const conditions: { input: string; value: unknown; source: BasedOn['source']; applies: boolean; reason: string; ruleId?: string }[] = [
    { input: 'household.misting_enabled', value: input.household?.misting_enabled ?? true, source: 'setting', applies: input.household?.misting_enabled !== false, reason: 'Household misting is disabled.' },
    { input: 'plant.misting_enabled', value: input.plant.misting_enabled ?? true, source: 'setting', applies: input.plant.misting_enabled !== false, reason: 'Plant misting is disabled.' },
    { input: 'site.location', value: input.site.location ?? 'not set', source: 'rule', ruleId: 'mist.applicability@1', applies: input.site.location === 'indoor', reason: 'Misting is only for indoor plants.' },
    { input: 'site.humidity', value: input.site.humidity ?? 'not set', source: 'rule', ruleId: 'mist.applicability@1', applies: input.site.humidity === 'dry' || input.site.humidity === 'normal', reason: 'Misting requires dry or normal site humidity.' },
    { input: 'humidityNeed', value: need.value, source: need.source, ruleId: need.ruleId, applies: need.value === 'medium' || need.value === 'high', reason: 'This humidity need does not call for misting.' },
  ];
  return { applicable: conditions.every(condition => condition.applies), basedOn: conditions.map(({ applies, reason, ...basis }) => ({
    ...basis, adjustment: applies ? 'Misting condition met; no interval adjustment.' : reason,
  })) };
}
export const taskRules: Record<TaskType, TaskRules> = {
  water: {
    label: 'Water', action: 'watering', profileField: 'wateringIntervalDays', minDays: 1, maxDays: 60, monthFactors: { values: wateringMonthFactors, ruleId: 'water.month@1', reason: 'Northern-hemisphere winter months stretch watering intervals.' }, anchor: 'added_date',
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
  fertilize: {
    label: 'Fertilize', action: 'fertilizing', profileField: 'fertilizingIntervalDays', minDays: 1, maxDays: 730,
    factors: [], anchor: 'added_plus_interval', season: { firstMonth: 3, lastMonth: 9, ruleId: 'fertilize.season@1' },
    defaultInterval: (input, intervals) => input.plant.fertilizer_type === 'sticks'
      ? intervalRule(56, 'fertilize.sticks@1', 'Sticks release nutrients over roughly eight weeks.')
      : input.plant.fertilizer_type === 'slow_release'
        ? intervalRule(90, 'fertilize.slow_release@1', 'Slow-release granules supply nutrients for roughly three months.')
        : intervalRule(2 * intervals('water'), 'fertilize.every_second_watering@1', 'Liquid or unknown fertilizer defaults to every second current watering.'),
  },
  mist: {
    label: 'Mist', action: 'misting', minDays: 1, maxDays: 365, factors: [], anchor: 'added_plus_interval',
    defaultInterval: intervalRule(3, 'mist.default@1', 'Three-day reminders prompt a check of humidity-loving indoor foliage.'),
    applicability: mistApplicability,
  },
  clean_leaves: {
    label: 'Clean leaves', action: 'leaf cleaning', minDays: 1, maxDays: 365, factors: [], anchor: 'added_plus_interval',
    defaultInterval: intervalRule(30, 'clean_leaves.default@1', 'Monthly cleaning limits indoor dust on foliage.'),
    applicability: input => ({ applicable: input.site.location === 'indoor', basedOn: [{ input: 'site.location', value: input.site.location ?? 'not set', source: 'rule', ruleId: 'clean_leaves.indoor@1', adjustment: input.site.location === 'indoor' ? 'Indoor leaves collect dust.' : 'Leaf cleaning is only for indoor plants.' }] }),
  },
  remove_dead: {
    label: 'Remove dead flowers and leaves', action: 'removing dead flowers and leaves', minDays: 1, maxDays: 365, factors: [], anchor: 'added_plus_interval',
    defaultInterval: intervalRule(14, 'remove_dead.default@1', 'Fortnightly checks catch dead foliage and spent flowers.'),
  },
  cut_back: {
    label: 'Seasonal cut-back', action: 'seasonal cut-back', minDays: 1, maxDays: 365, factors: [], anchor: pruningAnchor,
    defaultInterval: intervalRule(365, 'cut_back.annual@1', 'Prune once yearly in the species pruning month, defaulting to March.'),
  },
  repot: {
    label: 'Repot', action: 'repotting', minDays: 1, maxDays: 3650, factors: [], anchor: 'added_plus_interval',
    defaultInterval: input => intervalRule((input.pot.pot_size_cm ?? 20) <= 20 ? 730 : 1095, 'repot.pot_size@1', 'Small or unknown pots need a root-space check every two years; larger pots every three.'),
    applicability: input => ({ applicable: input.pot.pot_type !== 'in_ground', basedOn: [
      { input: 'pot.pot_type', value: input.pot.pot_type ?? 'not set', source: 'rule', ruleId: 'repot.potted@1', adjustment: input.pot.pot_type === 'in_ground' ? 'In-ground plants do not need repotting.' : 'Potted plants need root-space checks.' },
      { input: 'pot.pot_size_cm', value: input.pot.pot_size_cm ?? 'not set', source: 'rule', ruleId: 'repot.pot_size@1', adjustment: 'Above 20 cm: 1095 days; otherwise 730 days.' },
    ] }),
  },
};

import { expect, test } from 'vitest';
import { schedule, type ScheduleInput, type TaskType } from '../../src/lib/schedule/engine';

const input: ScheduleInput = {
  careProfile: {}, site: { location: 'indoor', humidity: 'normal' }, pot: {}, plant: {},
  history: {}, customIntervals: {}, today: '2026-05-01', addedOn: '2026-05-01',
};

test.each([
  ['2026-05-01', '2026-05-08', 7],
  ['2026-12-01', '2026-12-12', 11],
  ['2026-03-01', '2026-03-10', 9],
  ['2026-10-01', '2026-10-09', 8],
] as const)('watering uses anchor month %s, due %s after %i days', (lastCompletedOn, dueOn, intervalDays) => {
  expect(schedule({ ...input, history: { water: { lastCompletedOn } } }).water).toMatchObject({ dueOn, intervalDays });
});

const defaults: [TaskType, number, string][] = [
  ['water', 7, '2026-05-01'], ['fertilize', 14, '2026-05-15'], ['mist', 3, '2026-05-04'],
  ['clean_leaves', 30, '2026-05-31'], ['remove_dead', 14, '2026-05-15'],
  ['cut_back', 365, '2027-03-01'], ['repot', 730, '2028-04-30'],
];
test.each(defaults)('%s starts at %i days, first due %s', (type, intervalDays, dueOn) => {
  expect(schedule(input)[type]).toMatchObject({ status: 'due', intervalDays, dueOn });
});

test.each([
  ['liquid', 14, '2026-05-15', 'fertilize.every_second_watering@1'],
  [undefined, 14, '2026-05-15', 'fertilize.every_second_watering@1'],
  ['sticks', 56, '2026-06-26', 'fertilize.sticks@1'],
  ['slow_release', 90, '2026-07-30', 'fertilize.slow_release@1'],
] as const)('fertilizer %s gives %i days, due %s', (fertilizer_type, intervalDays, dueOn, ruleId) => {
  expect(schedule({ ...input, plant: { fertilizer_type } }).fertilize).toMatchObject({ intervalDays, dueOn,
    basedOn: expect.arrayContaining([expect.objectContaining({ ruleId, value: intervalDays })]) });
});

test.each([
  ['2026-09-25', '2027-03-01', 14], ['2026-01-01', '2026-03-01', 22],
  ['2026-03-01', '2026-03-19', 18],
] as const)('fertilizing added %s shifts to %s with a %i-day interval', (addedOn, dueOn, intervalDays) => {
  expect(schedule({ ...input, addedOn }).fertilize).toMatchObject({ dueOn, intervalDays });
});

test('fertilizing uses a supplied profile interval and its source', () => {
  expect(schedule({ ...input, careProfile: { fertilizingIntervalDays: { value: 21, source: 'open_plantbook' } } }).fertilize).toMatchObject({
    dueOn: '2026-05-22', intervalDays: 21, basedOn: expect.arrayContaining([expect.objectContaining({ input: 'fertilizingIntervalDays', value: 21, source: 'open_plantbook' })]),
  });
});

test.each([
  [{ household: { misting_enabled: false } }, 'household.misting_enabled', 'Household misting is disabled.'],
  [{ plant: { misting_enabled: false } }, 'plant.misting_enabled', 'Plant misting is disabled.'],
  [{ site: { location: 'outdoor', humidity: 'normal' } }, 'site.location', 'Misting is only for indoor plants.'],
  [{ site: { location: 'indoor', humidity: 'humid' } }, 'site.humidity', 'Misting requires dry or normal site humidity.'],
  [{ careProfile: { humidityNeed: { value: 'low', source: 'perenual' } } }, 'humidityNeed', 'This humidity need does not call for misting.'],
] satisfies [Partial<ScheduleInput>, string, string][])('misting is not applicable for %j', (change, field, adjustment) => {
  expect(schedule({ ...input, ...change }).mist).toMatchObject({ status: 'not_applicable',
    basedOn: expect.arrayContaining([expect.objectContaining({ input: field, adjustment })]) });
});
test.each(['dry', 'normal'] as const)('misting is applicable for medium need in %s humidity with both switches on', humidity => {
  expect(schedule({ ...input, site: { location: 'indoor', humidity }, household: { misting_enabled: true }, plant: { misting_enabled: true } }).mist).toMatchObject({ status: 'due', dueOn: '2026-05-04' });
});
test('high humidity need preserves its source in misting eligibility', () => {
  expect(schedule({ ...input, careProfile: { humidityNeed: { value: 'high', source: 'perenual' } } }).mist).toMatchObject({ status: 'due',
    basedOn: expect.arrayContaining([expect.objectContaining({ input: 'humidityNeed', value: 'high', source: 'perenual' })]) });
});

test.each([
  ['clean_leaves', { site: { location: 'outdoor' } }], ['repot', { pot: { pot_type: 'in_ground' } }],
] satisfies [TaskType, Partial<ScheduleInput>][])('%s is not applicable for %j even with an override', (type, change) => {
  expect(schedule({ ...input, ...change, customIntervals: { [type]: 5 } })[type]).toMatchObject({ status: 'not_applicable' });
});
test.each([[20, 730, '2028-04-30'], [21, 1095, '2029-04-30'], [null, 730, '2028-04-30']] as const)('repot a %s cm pot at %i days', (pot_size_cm, intervalDays, dueOn) => {
  expect(schedule({ ...input, pot: { pot_size_cm } }).repot).toMatchObject({ dueOn, intervalDays });
});

test.each([
  ['2026-03-01', undefined, undefined, '2026-03-01'],
  ['2026-03-02', undefined, undefined, '2027-03-01'],
  ['2026-01-15', undefined, [9, 3], '2026-03-01'],
  ['2026-05-01', undefined, [9, 3], '2026-09-01'],
  ['2026-05-01', '2026-09-12', [9, 3], '2027-03-01'],
  ['2026-05-01', '2027-03-01', undefined, '2028-03-01'],
] as const)('cut-back added %s, completed %s, months %j is due %s', (addedOn, lastCompletedOn, months, dueOn) => {
  const careProfile: ScheduleInput['careProfile'] = months ? { pruningMonths: { value: [...months], source: 'perenual' as const } } : {};
  expect(schedule({ ...input, addedOn, history: { cut_back: { lastCompletedOn } }, careProfile }).cut_back).toMatchObject({ dueOn,
    basedOn: expect.arrayContaining([expect.objectContaining({ input: 'pruningMonths', source: months ? 'perenual' : 'rule' })]) });
});

const overrides: [TaskType, string][] = [
  ['water', '2026-05-06'], ['fertilize', '2026-05-06'], ['mist', '2026-05-06'], ['clean_leaves', '2026-05-06'],
  ['remove_dead', '2026-05-06'], ['cut_back', '2026-05-06'], ['repot', '2026-05-06'],
];
test.each(overrides)('custom interval for %s replaces only its computed interval', (type, dueOn) => {
  const withHistory = { ...input, history: { [type]: { lastCompletedOn: '2026-05-01' } } };
  const before = schedule(withHistory);
  const after = schedule({ ...withHistory, customIntervals: { [type]: 5 } });
  expect(after[type]).toMatchObject({ intervalDays: 5, dueOn, basedOn: expect.arrayContaining([expect.objectContaining({ input: 'customIntervals', value: 5, source: 'override' })]) });
  // Fertilizing is derived from water unless it has its own interval.
  const unaffected = type === 'remove_dead' ? 'clean_leaves' : 'remove_dead';
  expect(after[unaffected]).toEqual(before[unaffected]);
});
test('custom intervals are not multiplied or clamped by watering factors', () => {
  expect(schedule({ ...input, site: { light: 'full_sun' }, customIntervals: { water: 365 }, history: { water: { lastCompletedOn: '2026-05-01' } } }).water).toMatchObject({ intervalDays: 365, dueOn: '2027-05-01' });
});
test('every-second-watering fertilizing follows even a long custom watering interval', () => {
  expect(schedule({ ...input, customIntervals: { water: 365 } }).fertilize).toMatchObject({
    intervalDays: 730, dueOn: '2028-04-30',
    basedOn: expect.arrayContaining([expect.objectContaining({ ruleId: 'fertilize.every_second_watering@1', value: 730 })]),
  });
});

test('basedOn records real weather, the anchor source and month adjustment without mutating input', () => {
  const example = { ...input, site: { ...input.site, location: 'outdoor' as const }, weather: { temperature_c: 25, rain_mm: 4 }, customIntervals: { water: 12 } };
  const before = structuredClone(example);
  expect(schedule(example).water).toMatchObject({ basedOn: expect.arrayContaining([
    expect.objectContaining({ input: 'weather', value: { temperature_c: 25, rain_mm: 4 }, source: 'weather' }),
    expect.objectContaining({ input: 'addedOn', value: '2026-05-01', source: 'history' }),
    expect.objectContaining({ input: 'month', value: 5, factor: 1, ruleId: 'water.month@1' }),
    expect.objectContaining({ input: 'customIntervals', value: 12, source: 'override' }),
  ]) });
  expect(example).toEqual(before);
});

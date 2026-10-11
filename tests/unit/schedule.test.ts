import { expect, test } from 'vitest';
import { schedule, type ScheduleInput } from '../../src/lib/schedule/engine';

const input: ScheduleInput = {
  careProfile: {}, site: {}, pot: {}, plant: {}, history: {}, customIntervals: {},
  today: '2026-05-01', addedOn: '2026-05-01',
};

test.each([
  ['full_shade', 56], ['part_shade', 48], ['part_sun', 40], ['full_sun', 28],
] as const)('site light %s changes a 40-day Perenual interval to %i days', (light, days) => {
  const result = schedule({ ...input, careProfile: { wateringIntervalDays: { value: 40, source: 'perenual' } }, site: { light } }).water;
  expect(result).toMatchObject({ intervalDays: days, basedOn: expect.arrayContaining([
    expect.objectContaining({ input: 'site.light', value: light, source: 'rule', ruleId: 'water.light@1', factor: expect.any(Number) }),
  ]) });
});

// Worked examples from a 40-day Perenual baseline, each with just one changed condition.
const factors: [string, Partial<ScheduleInput>, number][] = [
  ['dry humidity', { site: { humidity: 'dry' } }, 30],
  ['humid air', { site: { humidity: 'humid' } }, 50],
  ['normal humidity', { site: { humidity: 'normal' } }, 40],
  ['outdoor', { site: { location: 'outdoor' } }, 32],
  ['indoor', { site: { location: 'indoor' } }, 40],
  ['site grow light', { site: { grow_light: true } }, 32],
  ['plant grow light', { plant: { grow_light: true } }, 32],
  ['one grow light inherited at both levels', { site: { grow_light: true }, plant: { grow_light: true } }, 32],
  ['draft', { site: { draft: true } }, 34],
  ['terracotta', { pot: { pot_type: 'terracotta' } }, 30],
  ['plastic', { pot: { pot_type: 'plastic' } }, 40],
  ['ceramic', { pot: { pot_type: 'ceramic' } }, 40],
  ['glass', { pot: { pot_type: 'glass' } }, 40],
  ['metal', { pot: { pot_type: 'metal' } }, 40],
  ['wood', { pot: { pot_type: 'wood' } }, 34],
  ['fabric', { pot: { pot_type: 'fabric' } }, 28],
  ['in ground', { pot: { pot_type: 'in_ground' } }, 56],
  ['small pot, 12cm', { pot: { pot_size_cm: 12 } }, 30],
  ['medium pot, 13cm', { pot: { pot_size_cm: 13 } }, 40],
  ['medium pot, 25cm', { pot: { pot_size_cm: 25 } }, 40],
  ['large pot, 26cm', { pot: { pot_size_cm: 26 } }, 52],
  ['no drainage', { pot: { drainage: false } }, 52],
  ['drainage', { pot: { drainage: true } }, 40],
  ['small plant', { plant: { plant_size: 'small' } }, 44],
  ['medium plant', { plant: { plant_size: 'medium' } }, 40],
  ['large plant', { plant: { plant_size: 'large' } }, 34],
  ['very large plant', { plant: { plant_size: 'very_large' } }, 28],
  ['seedling', { plant: { growth_stage: 'seedling' } }, 28],
  ['young', { plant: { growth_stage: 'young' } }, 36],
  ['mature', { plant: { growth_stage: 'mature' } }, 40],
  ['close window, 50cm', { plant: { window_distance_cm: 50 } }, 34],
  ['mid-distance window, 51cm', { plant: { window_distance_cm: 51 } }, 40],
  ['mid-distance window, 200cm', { plant: { window_distance_cm: 200 } }, 40],
  ['far window, 201cm', { plant: { window_distance_cm: 201 } }, 50],
  ['near AC', { plant: { near_ac: true } }, 34],
  ['near heater', { plant: { near_heater: true } }, 28],
];
test.each(factors)('%s gives %j and a %i-day interval', (_name, change, days) => {
  expect(schedule({ ...input, careProfile: { wateringIntervalDays: { value: 40, source: 'perenual' } }, ...change }).water).toMatchObject({ intervalDays: days });
});

test.each([
  ['2026-10-24', '2026-11-01', '2026-11-01', 0],
  ['2026-10-20', '2026-10-28', '2026-10-29', 1],
  ['2026-03-25', '2026-04-03', '2026-04-05', 2],
  ['2026-12-28', '2027-01-08', '2027-01-01', 0],
] as const)('completion on %s is due on %s, late as of %s by %i calendar days', (lastCompletedOn, dueOn, today, lateDays) => {
  expect(schedule({ ...input, today, history: { water: { lastCompletedOn } } }).water).toMatchObject({ dueOn, lateDays });
});

test('no history preserves the added date and counts lateness in calendar days', () => {
  expect(schedule({ ...input, addedOn: '2026-10-24', today: '2026-10-26' }).water).toMatchObject({ dueOn: '2026-10-24', lateDays: 2 });
});

test.each([
  [undefined, 'addedOn', '2026-05-01', 'counted from the date the plant was added, 2026-05-01'],
  ['2026-10-10', 'history.water.lastCompletedOn', '2026-10-10', 'counted from last completion on 2026-10-10'],
] as const)('explains the due-date anchor with completion %s', (lastCompletedOn, anchor, value, adjustment) => {
  expect(schedule({ ...input, history: { water: { lastCompletedOn } } }).water).toMatchObject({
    basedOn: expect.arrayContaining([
      expect.objectContaining({ input: anchor, value, adjustment, source: 'history' }),
      expect.objectContaining({ input: 'customIntervals', value: 'not set', source: 'setting', adjustment: 'not set; no adjustment' }),
      expect.objectContaining({ input: 'weather', value: 'not set', source: 'weather', adjustment: 'not set; no adjustment' }),
    ]),
  });
});

test('a supplied care value retains its source instead of claiming to be a fallback rule', () => {
  expect(schedule({ ...input, careProfile: { wateringIntervalDays: { value: 10, source: 'perenual' } } }).water).toMatchObject({
    intervalDays: 10, basedOn: expect.arrayContaining([expect.objectContaining({ input: 'wateringIntervalDays', value: 10, source: 'perenual' })]),
  });
});

test('missing and unknown conditions are neutral and each is explained as not set', () => {
  const result = schedule({ ...input, site: {
    // External snapshots can contain a value this engine does not recognise.
    // @ts-expect-error deliberately unknown light
    light: 'unknown',
  }, pot: { pot_size_cm: null }, plant: { window_distance_cm: null } }).water;
  expect(result).toMatchObject({ intervalDays: 7 });
  expect(result.basedOn.filter(item => item.factor !== undefined && item.input !== 'month')).toHaveLength(14);
  for (const item of result.basedOn.filter(item => item.factor !== undefined && item.input !== 'month')) {
    expect(item).toMatchObject({ value: 'not set', factor: 1, adjustment: 'not set; no adjustment' });
  }
});

test('every factor is explained, including neutral factors, without changing the input', () => {
  const full = { ...input, site: { light: 'full_sun', humidity: 'dry', location: 'outdoor', grow_light: true, draft: true },
    pot: { pot_type: 'terracotta', pot_size_cm: 10, drainage: true },
    plant: { plant_size: 'very_large', growth_stage: 'seedling', window_distance_cm: 30, grow_light: false, near_ac: true, near_heater: true } } satisfies ScheduleInput;
  const before = structuredClone(full);
  const task = schedule(full).water;
  expect(task).toMatchObject({ intervalDays: 1 });
  expect(task.basedOn.map(item => item.input)).toEqual([
    'wateringIntervalDays', 'site.light', 'site.humidity', 'site.location', 'site.grow_light', 'site.draft',
    'pot.pot_type', 'pot.pot_size_cm', 'pot.drainage', 'plant.plant_size', 'plant.growth_stage',
    'plant.window_distance_cm', 'plant.grow_light', 'plant.near_ac', 'plant.near_heater',
    'month', 'customIntervals', 'weather', 'addedOn',
  ]);
  for (const item of task.basedOn.filter(item => item.factor !== undefined)) expect(item).toMatchObject({ source: 'rule', ruleId: expect.stringMatching(/@1$/), factor: expect.any(Number), adjustment: expect.any(String) });
  expect(full).toEqual(before);
});

test.each([[1000, 60], [.01, 1]] as const)('clamps a %i-day estimate to %i whole days', (value, intervalDays) => {
  expect(schedule({ ...input, careProfile: { wateringIntervalDays: { value, source: 'override' } } }).water).toMatchObject({ intervalDays });
});

test('snooze and skip history fields are reserved and do not change watering yet', () => {
  expect(schedule({ ...input, history: { water: { snoozedUntil: '2026-10-15', skippedOn: '2026-10-11' } } })).toEqual(schedule(input));
});

test('a plant without care history is due on its added date, using a named rule', () => {
  expect(schedule(input).water).toMatchObject({
    status: 'due', dueOn: '2026-05-01', intervalDays: 7, lateDays: 0,
    basedOn: expect.arrayContaining([
      expect.objectContaining({ input: 'wateringIntervalDays', value: 7, source: 'rule', ruleId: 'water.default@1' }),
    ]),
  });
});

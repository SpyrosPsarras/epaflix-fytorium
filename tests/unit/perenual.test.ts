import { expect, test } from 'vitest';
import { mapPerenual } from '../../src/lib/server/care/perenual';

test.each([
  [{ value: '7-10', unit: 'days' }, 8.5],
  [{ value: '1-2', unit: 'weeks' }, 10.5],
  [{ value: '48', unit: 'hours' }, 2],
  [{ value: '2', unit: 'months' }, 60],
  [{ value: '"7-10"', unit: 'days' }, 8.5],
  [{ value: '  " 1-2 "  ', unit: ' "weeks" ' }, 10.5],
  [{ value: " '48' ", unit: ' hours ' }, 2],
])('converts a watering benchmark to days using the range midpoint', (benchmark, days) => {
  expect(mapPerenual({ watering_general_benchmark: benchmark }).wateringIntervalDays).toBe(days);
});

test.each([
  ['full shade', 'full_shade'], ['part_shade', 'part_shade'], ['sun-part_shade', 'part_sun'], ['full sun', 'full_sun'],
])('maps sunlight %s to the site light enum', (sunlight, light) => {
  expect(mapPerenual({ sunlight: [sunlight] }).light).toBe(light);
});

test('maps pruning months, booleans, lifecycle, size, hardiness and pests without inventing humidity or temperatures', () => {
  expect(mapPerenual({ pruning_month: ['March', 'April'], hardiness: { min: '9', max: '11' }, poisonous_to_pets: false, poisonous_to_humans: true, cycle: 'Perennial', dimensions: { min_value: 1, max_value: 2, unit: 'feet' }, pest_susceptibility: ['Scale insects'], indoor: true })).toEqual({ pruningMonths: [3, 4], hardiness: { min: 9, max: 11 }, poisonousToPets: false, poisonousToHumans: true, cycle: 'perennial', dimensions: { min: 30.48, max: 60.96, unit: 'cm' }, pests: ['Scale insects'], indoor: true });
});

test.each([
  null, {}, { watering_general_benchmark: { value: 'upgrade', unit: 'days' }, sunlight: ['Unknown'], pruning_month: ['Soon'], hardiness: { min: '', max: '' }, poisonous_to_pets: 'premium', cycle: 'upgrade' },
  { watering_general_benchmark: { value: '10-7', unit: 'days' } },
  { watering_general_benchmark: { value: '10', unit: 'fortnights' } },
])('ignores unavailable and unmappable fields', payload => {
  expect(mapPerenual(payload)).toEqual({});
});

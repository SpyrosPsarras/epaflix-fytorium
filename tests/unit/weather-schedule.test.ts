import { expect, test } from 'vitest';
import { schedule, type ScheduleInput } from '../../src/lib/schedule/engine';

const input: ScheduleInput = {
  careProfile: { wateringIntervalDays: { value: 10, source: 'override' } },
  site: { location: 'outdoor' }, pot: {}, plant: {}, history: {}, customIntervals: {},
  today: '2030-06-01', addedOn: '2030-06-01',
};

test.each([
  ['hot week', { meanMaxTemperatureC: 30, daylightHours: 12 }, 6],
  ['cold week', { meanMaxTemperatureC: 10, daylightHours: 12 }, 10],
  ['short days', { meanMaxTemperatureC: 20, daylightHours: 8 }, 10],
  ['missing weather', undefined, 8],
] as const)('%s gives a literal outdoor watering interval', (_, weather, intervalDays) => {
  expect(schedule({ ...input, weather }).water).toMatchObject({ intervalDays });
});

test('indoor watering ignores a supplied hot and short-day snapshot', () => {
  const task = schedule({ ...input, site: { location: 'indoor' }, weather: { meanMaxTemperatureC: 30, daylightHours: 6 } }).water;
  expect(task).toMatchObject({ intervalDays: 10 });
  expect(task.basedOn.filter(row => row.factor !== undefined && row.source === 'weather')).toEqual([]);
});

test('outdoor weather inputs explain their source and versioned adjustments before the clamp', () => {
  const task = schedule({ ...input, weather: { meanMaxTemperatureC: 30, daylightHours: 8 } }).water;
  expect(task.basedOn).toEqual(expect.arrayContaining([
    expect.objectContaining({ input: 'weather.meanMaxTemperatureC', value: 30, source: 'weather', factor: .75, ruleId: 'water.weather_temperature@1' }),
    expect.objectContaining({ input: 'weather.daylightHours', value: 8, source: 'weather', factor: 1.25, ruleId: 'water.weather_daylight@1' }),
  ]));
  expect(schedule({ ...input, careProfile: { wateringIntervalDays: { value: 1000, source: 'override' } }, weather: { meanMaxTemperatureC: 10, daylightHours: 6 } }).water).toMatchObject({ intervalDays: 60 });
});

import { expect, test } from 'vitest';
import { schedule, type ScheduleInput } from '../../src/lib/schedule/engine';
import { mapOpenPlantbook } from '../../src/lib/care/open-plantbook';

test.each([
  [0, 38, 'low'], [30, 50, 'medium'], [50, 70, 'high'], [60, 100, 'high'],
])('maps humidity %s–%s using open_plantbook.humidity@1', (min, max, expected) => {
  expect(mapOpenPlantbook({ min_env_humid: min, max_env_humid: max }).humidityNeed).toBe(expected);
});

test.each([
  [0, 1999, 'full_shade'], [1000, 3000, 'part_shade'], [4000, 6000, 'part_sun'], [10000, 30000, 'full_sun'],
])('maps lux %s–%s using open_plantbook.light@1', (min, max, expected) => {
  expect(mapOpenPlantbook({ min_light_lux: min, max_light_lux: max }).light).toBe(expected);
});

test.each([
  [15, 28, -5, { min: 15, max: 28 }, -5], [-20, 0, -30, { min: -20, max: 0 }, -30],
  [-60, 60, 0, { min: -60, max: 60 }, 0],
])('keeps temperature and survival values in Celsius', (min, max, survival, expected, hardiness) => {
  expect(mapOpenPlantbook({ min_temp: min, max_temp: max, min_temp_survival: survival })).toEqual({ temperatureRangeC: expected, hardinessC: hardiness });
});

test('keeps sensor soil moisture and care text without deriving watering intervals', () => {
  expect(mapOpenPlantbook({ min_soil_moist: 20, max_soil_moist: 60, care: { watering: 'Synthetic watering advice.', soil: 'Synthetic soil advice.' } })).toEqual({ soilMoistureRangePercent: { min: 20, max: 60 }, careText: 'soil: Synthetic soil advice.\nwatering: Synthetic watering advice.' });
});

test.each([
  [10, 30, 'low', 'not_applicable'], [30, 50, 'medium', 'due'], [50, 70, 'high', 'due'],
])('mapped humidity %s–%s changes misting and Based on', (min, max, expected, status) => {
  const mapped = mapOpenPlantbook({ min_env_humid: min, max_env_humid: max });
  const input: ScheduleInput = { careProfile: { humidityNeed: { value: mapped.humidityNeed, source: 'open_plantbook', fetchedAt: '2030-06-01T12:00:00.000Z' } }, site: { location: 'indoor', humidity: 'normal' }, pot: {}, plant: {}, history: {}, customIntervals: {}, addedOn: '2030-06-01', today: '2030-06-01' };
  expect(schedule(input).mist).toMatchObject({ status, basedOn: expect.arrayContaining([expect.objectContaining({ input: 'humidityNeed', value: expected, source: 'open_plantbook', fetchedAt: '2030-06-01T12:00:00.000Z' })]) });
  expect(schedule({ ...input, careProfile: { humidityNeed: { value: 'low', source: 'override' } } }).mist).toMatchObject({ status: 'not_applicable', basedOn: expect.arrayContaining([expect.objectContaining({ value: 'low', source: 'override' })]) });
});

test.each([
  null, {}, { min_env_humid: -1, max_env_humid: 50 }, { min_env_humid: 50, max_env_humid: 101 },
  { min_env_humid: 60, max_env_humid: 40 }, { min_env_humid: '40', max_env_humid: 60 },
  { min_light_lux: 100, max_light_lux: 100001 }, { min_light_lux: 500, max_light_lux: 100 },
  { min_temp: -61, max_temp: 10, min_temp_survival: 61 }, { min_temp: 30, max_temp: 10 },
  { min_temp: null, max_temp: 20 }, { min_env_humid: NaN, max_env_humid: Infinity },
])('unknown and invalid sensor values leave fallbacks intact', payload => {
  expect(mapOpenPlantbook(payload)).toEqual({});
});

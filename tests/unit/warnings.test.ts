import { expect, test } from 'vitest';
import { plantWarnings, type WarningInput } from '../../src/lib/warnings';

const healthy: WarningInput = {
  careProfile: { light: { value: 'part_sun', source: 'perenual', fetchedAt: '2030-06-01T12:00:00Z' } },
  site: { light: 'part_sun', location: 'indoor', grow_light: false },
  pot: { pot_type: 'plastic', pot_size_cm: 20, drainage: true },
  plant: { grow_light: false, window_distance_cm: 100 },
  locationSet: true, status: 'active',
};

test.each([
  ['matched light', 'part_sun', false, false, 100, []],
  ['one step too dark', 'part_shade', false, false, 100, ['too_dark']],
  ['one step too bright', 'full_sun', false, false, 100, ['too_bright']],
  ['plant grow light compensates', 'part_shade', false, true, 100, []],
  ['site grow light compensates', 'part_shade', true, false, 100, []],
  ['both grow lights count once', 'part_shade', true, true, 100, []],
  ['200 cm is not far', 'part_sun', false, false, 200, []],
  ['201 cm lowers light', 'part_sun', false, false, 201, ['too_dark']],
  ['far window offsets grow light', 'part_sun', false, true, 201, []],
  ['unknown distance is neutral', 'part_sun', false, false, null, []],
  ['upper scale clamps', 'full_sun', true, true, 100, ['too_bright']],
  ['lower scale clamps', 'full_shade', false, false, 201, ['too_dark']],
] as const)('%s', (_, light, siteGrow, plantGrow, distance, kinds) => {
  expect(plantWarnings({ ...healthy, site: { ...healthy.site, light, grow_light: siteGrow },
    plant: { grow_light: plantGrow, window_distance_cm: distance } }).map(w => w.kind)).toEqual(kinds);
});

test('light mismatch retains the species source and every effective-light input', () => {
  expect(plantWarnings({ ...healthy, plant: { grow_light: true, window_distance_cm: 250 } , site: { ...healthy.site, light: 'part_shade' } })).toEqual([{
    kind: 'too_dark', reason: 'Effective light is part_shade; species needs part_sun. A gap of at least one light step is too dark.', inputs: [
      { input: 'careProfile.light', value: 'part_sun', source: 'perenual', fetchedAt: '2030-06-01T12:00:00Z' },
      { input: 'site.light', value: 'part_shade', source: 'setting', ruleId: 'warning.effective_light@1' },
      { input: 'site.grow_light', value: false, source: 'setting', ruleId: 'warning.effective_light@1' },
      { input: 'plant.grow_light', value: true, source: 'setting', ruleId: 'warning.effective_light@1' },
      { input: 'plant.window_distance_cm', value: 250, source: 'setting', ruleId: 'warning.effective_light@1' },
      { input: 'effective_light', value: 'part_shade', source: 'rule', ruleId: 'warning.effective_light@1' },
    ],
  }]);
});

test.each([
  ['site light', { site: { ...healthy.site, light: undefined } }, 'site.light'],
  ['pot type', { pot: { ...healthy.pot, pot_type: undefined } }, 'pot.pot_type'],
  ['pot size', { pot: { ...healthy.pot, pot_size_cm: null } }, 'pot.pot_size_cm'],
  ['species care', { careProfile: { wateringIntervalDays: { value: 7, source: 'rule' } } }, 'careProfile'],
  ['unknown care', { careProfile: { light: { value: null, source: 'unknown' } } }, 'careProfile'],
  ['outdoor location', { site: { ...healthy.site, location: 'outdoor' }, locationSet: false }, 'household.location'],
] satisfies [string, Partial<WarningInput>, string][])('missing %s carries the missing input and version', (_, patch, field) => {
  const warnings = plantWarnings({ ...healthy, ...patch });
  expect(warnings.map(w => w.kind)).toEqual(['missing_info']);
  expect(warnings[0].inputs).toHaveLength(1);
  expect(warnings[0].inputs[0]).toMatchObject({ input: field, ruleId: 'warning.missing_info@1' });
});

test('missing information lists all gaps in a fixed order', () => {
  expect(plantWarnings({ careProfile: {}, site: { location: 'outdoor' }, pot: {}, plant: {}, locationSet: false, status: 'active' })).toEqual([{
    kind: 'missing_info', reason: 'Missing information: site light, pot type, pot size, species care data beyond rules, household location for an outdoor plant.', inputs: [
      { input: 'site.light', value: 'not set', source: 'setting', ruleId: 'warning.missing_info@1' },
      { input: 'pot.pot_type', value: 'not set', source: 'setting', ruleId: 'warning.missing_info@1' },
      { input: 'pot.pot_size_cm', value: 'not set', source: 'setting', ruleId: 'warning.missing_info@1' },
      { input: 'careProfile', value: {}, source: 'rule', ruleId: 'warning.missing_info@1' },
      { input: 'household.location', value: false, source: 'setting', ruleId: 'warning.missing_info@1' },
    ],
  }]);
});

test.each([
  ['healthy plant', healthy],
  ['indoor plant needs no household location', { ...healthy, locationSet: false }],
  ['in-ground plant needs no drainage', { ...healthy, pot: { ...healthy.pot, pot_type: 'in_ground', drainage: false } }],
  ['graveyard plant never warns', { careProfile: {}, site: {}, pot: { drainage: false }, plant: {}, locationSet: false, status: 'graveyard' }],
] satisfies [string, WarningInput][])('%s', (_, input) => { expect(plantWarnings(input)).toEqual([]); });

test('an owner override supplies species light and its source', () => {
  const warnings = plantWarnings({ ...healthy, careProfile: { light: { value: 'full_sun', source: 'override' } } });
  expect(warnings.map(w => w.kind)).toEqual(['too_dark']);
  expect(warnings[0].inputs[0]).toEqual({ input: 'careProfile.light', value: 'full_sun', source: 'override' });
});

test('a pot without drainage explains its inputs', () => {
  expect(plantWarnings({ ...healthy, pot: { ...healthy.pot, drainage: false } })).toEqual([{
    kind: 'no_drainage', reason: 'A pot without drainage can retain excess water.', inputs: [
      { input: 'pot.pot_type', value: 'plastic', source: 'setting', ruleId: 'warning.drainage@1' },
      { input: 'pot.drainage', value: false, source: 'setting', ruleId: 'warning.drainage@1' },
    ],
  }]);
});

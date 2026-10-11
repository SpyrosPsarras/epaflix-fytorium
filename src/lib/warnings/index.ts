import type { CareField, ScheduleInput } from '../schedule/engine';
import { lightLevels } from '../sites';

export type WarningInput = Pick<ScheduleInput, 'careProfile' | 'site' | 'pot' | 'plant'> & {
  locationSet: boolean; status: 'active' | 'graveyard';
};
export type PlantWarning = {
  kind: 'too_dark' | 'too_bright' | 'no_drainage' | 'missing_info';
  reason: string;
  inputs: (Omit<CareField, 'source'> & { input: string; source: CareField['source'] | 'setting' })[];
};
export function plantWarnings(input: WarningInput): PlantWarning[] {
  if (input.status === 'graveyard') return [];
  const warnings: PlantWarning[] = [];
  const need = input.careProfile.light;
  const siteLevel = lightLevels.findIndex(level => level === input.site.light);
  const needLevel = lightLevels.findIndex(level => level === need?.value);
  if (siteLevel >= 0 && needLevel >= 0) {
    // warning.effective_light@1: adjust together, then clamp on the four-step scale.
    const grow = input.site.grow_light === true || input.plant.grow_light === true;
    const far = (input.plant.window_distance_cm ?? 0) > 200;
    const effective = Math.max(0, Math.min(lightLevels.length - 1, siteLevel + Number(grow) - Number(far)));
    if (effective !== needLevel) {
      const direction = effective < needLevel ? 'dark' : 'bright';
      warnings.push({ kind: effective < needLevel ? 'too_dark' : 'too_bright',
        reason: `Effective light is ${lightLevels[effective]}; species needs ${need.value}. A gap of at least one light step is too ${direction}.`,
        inputs: [
          { input: 'careProfile.light', ...need },
          ...[
            ['site.light', input.site.light], ['site.grow_light', input.site.grow_light],
            ['plant.grow_light', input.plant.grow_light], ['plant.window_distance_cm', input.plant.window_distance_cm],
          ].map(([field, value]) => ({ input: String(field), value: value ?? 'not set', source: 'setting' as const, ruleId: 'warning.effective_light@1' })),
          { input: 'effective_light', value: lightLevels[effective], source: 'rule', ruleId: 'warning.effective_light@1' },
        ],
      });
    }
  }
  if (input.pot.pot_type !== 'in_ground' && input.pot.drainage === false) warnings.push({
    kind: 'no_drainage', reason: 'A pot without drainage can retain excess water.', inputs: [
      { input: 'pot.pot_type', value: input.pot.pot_type ?? 'not set', source: 'setting', ruleId: 'warning.drainage@1' },
      { input: 'pot.drainage', value: false, source: 'setting', ruleId: 'warning.drainage@1' },
    ],
  });
  const missing: { label: string; basis: PlantWarning['inputs'][number] }[] = [];
  for (const [field, label, value] of [
    ['site.light', 'site light', input.site.light],
    ['pot.pot_type', 'pot type', input.pot.pot_type],
    ['pot.pot_size_cm', 'pot size', input.pot.pot_size_cm],
  ] as const) if (value == null) missing.push({ label, basis: { input: field, value: 'not set', source: 'setting', ruleId: 'warning.missing_info@1' } });
  if (!Object.values(input.careProfile).some(field => field.value != null && ['override', 'perenual', 'open_plantbook'].includes(field.source))) {
    missing.push({ label: 'species care data beyond rules', basis: { input: 'careProfile', value: input.careProfile, source: 'rule', ruleId: 'warning.missing_info@1' } });
  }
  if (input.site.location === 'outdoor' && !input.locationSet) missing.push({ label: 'household location for an outdoor plant',
    basis: { input: 'household.location', value: false, source: 'setting', ruleId: 'warning.missing_info@1' } });
  if (missing.length) warnings.push({ kind: 'missing_info', reason: `Missing information: ${missing.map(gap => gap.label).join(', ')}.`, inputs: missing.map(gap => gap.basis) });
  return warnings;
}

import type { CareField, ScheduleInput } from '$lib/schedule/engine';
import { hardinessRules } from '$lib/schedule/rules';

export function groundHardiness(profile: ScheduleInput['careProfile']): CareField & { value: number } {
  const celsius = profile.hardinessC;
  if (typeof celsius?.value === 'number' && Number.isFinite(celsius.value)) return { ...celsius, value: celsius.value };
  const zones = profile.hardiness;
  const min = zones?.value && typeof zones.value === 'object' && 'min' in zones.value ? zones.value.min : undefined;
  if (typeof min === 'number' && min >= 1 && min <= 13) {
    return { ...zones, value: hardinessRules.zoneMinimumC[Math.floor(min) - 1], ruleId: hardinessRules.zoneId };
  }
  return { value: hardinessRules.defaultC, source: 'rule', ruleId: hardinessRules.defaultId };
}
export function applicableHardiness(profile: ScheduleInput['careProfile'], potType: string) {
  const hardiness = groundHardiness(profile);
  return { hardiness, thresholdC: hardiness.value + (potType === 'in_ground' ? 0 : hardinessRules.potOffsetC),
    ...(potType === 'in_ground' ? {} : { potRuleId: hardinessRules.potId }) };
}

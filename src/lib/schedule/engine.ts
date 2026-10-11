import type { SiteInput } from '../sites';
import type { PlantInput } from '../plants';
import { taskRules, type FactorInput } from './rules';

export const taskTypes = ['water'] as const;
export type TaskType = typeof taskTypes[number];
export type CareField = { value: unknown; source: 'override' | 'perenual' | 'open_plantbook' | 'rule'; ruleId?: string };
export type BasedOn = CareField & { input: string; adjustment: string; factor?: number };
export type Task = { status: 'due'; dueOn: string; intervalDays: number; lateDays: number; basedOn: BasedOn[] }
  | { status: 'not_applicable'; basedOn: BasedOn[] };
export type ScheduleInput = {
  careProfile: Record<string, CareField>;
  site: Partial<SiteInput>;
  pot: Partial<Pick<PlantInput, 'pot_type' | 'pot_size_cm' | 'drainage' | 'soil_type'>>;
  plant: Partial<Pick<PlantInput, 'plant_size' | 'growth_stage' | 'window_distance_cm' | 'grow_light' | 'near_ac' | 'near_heater' | 'fertilizer_type'>>;
  history: Partial<Record<TaskType, { lastCompletedOn?: string; snoozedUntil?: string; skippedOn?: string }>>;
  customIntervals: Partial<Record<TaskType, number>>;
  today: string;
  addedOn: string;
  weather?: Readonly<Record<string, unknown>>;
};

function factorValue(input: ScheduleInput, key: FactorInput) {
  switch (key.group) {
    case 'site': return input.site[key.field];
    case 'pot': return input.pot[key.field];
    case 'plant': return input.plant[key.field];
  }
}

// Dates at this seam are ISO calendar dates, already resolved in the household time zone.
export function schedule(input: ScheduleInput): Record<TaskType, Task> {
  return Object.fromEntries(taskTypes.map(taskType => {
    const rules = taskRules[taskType];
    const supplied = input.careProfile[rules.profileField];
    const base = supplied && typeof supplied.value === 'number' && Number.isFinite(supplied.value) && supplied.value > 0 ? supplied : rules.defaultInterval;
    const basedOn: BasedOn[] = [{ input: rules.profileField, ...base,
      adjustment: base === rules.defaultInterval ? rules.defaultInterval.reason : 'Species care profile sets the starting interval.' }];
    let interval = base.value as number;
    for (const rule of rules.factors) {
      const value = factorValue(input, rule.input);
      const key = rule.bands
        ? typeof value === 'number' && Number.isFinite(value) && value > 0 ? rule.bands.find(([max]) => value <= max)?.[1] : undefined
        : String(value);
      const known = key !== undefined && Object.hasOwn(rule.factors, key);
      const neutral = rule.neutralWhen?.(input);
      const factor = known && !neutral ? rule.factors[key!] : 1;
      interval *= factor;
      basedOn.push({ input: `${rule.input.group}.${rule.input.field}`, value: known ? value : 'not set', source: 'rule', ruleId: rule.ruleId,
        factor, adjustment: !known ? 'not set; no adjustment' : neutral ? rule.neutralReason! : rule.reason });
    }
    const intervalDays = Math.max(rules.minDays, Math.min(rules.maxDays, Math.round(interval)));
    const completion = input.history[taskType]?.lastCompletedOn;
    basedOn.push({ input: completion ? `history.${taskType}.lastCompletedOn` : 'addedOn', value: completion ?? input.addedOn, source: 'rule',
      adjustment: completion ? `counted from last completion on ${completion}` : `counted from the date the plant was added, ${input.addedOn}` });
    basedOn.push(...['customIntervals', 'weather'].map(field => ({ input: field, value: 'not set', source: 'rule' as const, adjustment: 'not set; no adjustment' })));
    // UTC here represents plain calendar days, not instants in the household time zone.
    const due = new Date((completion ?? input.addedOn) + 'T00:00:00Z');
    if (completion) due.setUTCDate(due.getUTCDate() + intervalDays);
    const dueOn = due.toISOString().slice(0, 10);
    const lateDays = Math.max(0, Math.round((Date.parse(input.today + 'T00:00:00Z') - due.getTime()) / 86_400_000));
    return [taskType, { status: 'due', dueOn, intervalDays, lateDays, basedOn }];
  })) as Record<TaskType, Task>;
}

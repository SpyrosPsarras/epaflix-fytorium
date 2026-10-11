import type { SiteInput } from '../sites';
import type { PlantInput } from '../plants';
import { taskRules, taskTypes, type TaskType, type FactorInput, type TaskRules } from './rules';
export { taskTypes, type TaskType } from './rules';

export type CareField = { value: unknown; source: 'override' | 'perenual' | 'open_plantbook' | 'rule' | 'unknown'; ruleId?: string; fetchedAt?: string | null };
export type BasedOn = Omit<CareField, 'source'> & { source: CareField['source'] | 'history' | 'setting' | 'weather'; input: string; adjustment: string; factor?: number };
export type Task = { status: 'due'; dueOn: string; intervalDays: number; lateDays: number; basedOn: BasedOn[] }
  | { status: 'not_applicable'; basedOn: BasedOn[] };
export type TaskHistory = { lastCompletedOn?: string; snoozedUntil?: string; skippedOn?: string };
export type Schedule = Record<TaskType, Task> & { customTasks: Record<string, Task> };
export type ScheduleInput = {
  careProfile: Record<string, CareField>;
  site: Partial<SiteInput>;
  pot: Partial<Pick<PlantInput, 'pot_type' | 'pot_size_cm' | 'drainage' | 'soil_type'>>;
  plant: Partial<Pick<PlantInput, 'plant_size' | 'growth_stage' | 'window_distance_cm' | 'grow_light' | 'near_ac' | 'near_heater' | 'fertilizer_type'>> & { misting_enabled?: boolean };
  household?: { misting_enabled?: boolean };
  history: Partial<Record<string, TaskHistory>>;
  customTasks?: readonly { id: string; intervalDays: number; createdOn: string }[];
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

function intervalFor(input: ScheduleInput, type: TaskType) {
  const rules = taskRules[type];
  const fallback = typeof rules.defaultInterval === 'function' ? rules.defaultInterval(input, other => intervalFor(input, other).intervalDays) : rules.defaultInterval;
  const supplied = rules.profileField ? input.careProfile[rules.profileField] : undefined;
  const base = supplied && typeof supplied.value === 'number' && Number.isFinite(supplied.value) && supplied.value > 0 ? supplied : fallback;
  const basedOn: BasedOn[] = [{ input: rules.profileField ?? `${type}.intervalDays`, ...base,
    adjustment: base === fallback ? fallback.reason : 'Species care profile sets the starting interval.' }];
  let interval = base.value as number;
  for (const rule of rules.factors) {
    const value = factorValue(input, rule.input);
    const key = rule.bands
      ? typeof value === 'number' && Number.isFinite(value) && value > 0 ? rule.bands.find(([max]) => value <= max)?.[1] : undefined
      : String(value);
    const factors: Readonly<Record<string, number>> = rule.factors;
    const known = key !== undefined && Object.hasOwn(factors, key);
    const neutral = rule.neutralWhen?.(input);
    const factor = known && !neutral ? factors[key!] : 1;
    interval *= factor;
    basedOn.push({ input: `${rule.input.group}.${rule.input.field}`, value: known ? value : 'not set', source: 'rule', ruleId: rule.ruleId,
      factor, adjustment: !known ? 'not set; no adjustment' : neutral ? rule.neutralReason! : rule.reason });
  }
  if (rules.monthFactors) {
    const month = Number((input.history[type]?.lastCompletedOn ?? input.addedOn).slice(5, 7));
    const factor = rules.monthFactors.values[month - 1];
    interval *= factor;
    basedOn.push({ input: 'month', value: month, source: 'rule', ruleId: rules.monthFactors.ruleId, factor, adjustment: rules.monthFactors.reason });
  }
  let intervalDays = Math.max(rules.minDays, Math.min(rules.maxDays, Math.round(interval)));
  const override = input.customIntervals[type];
  const validOverride = typeof override === 'number' && Number.isInteger(override) && override >= 1 && override <= 365;
  if (validOverride) intervalDays = override;
  basedOn.push({ input: 'customIntervals', value: override ?? 'not set', source: override === undefined ? 'setting' : 'override',
    adjustment: validOverride ? `Custom ${rules.label.toLowerCase()} interval replaces the computed interval: ${override} days.` : override === undefined ? 'not set; no adjustment' : 'Invalid custom interval; no adjustment.' });
  return { intervalDays, basedOn, overridden: validOverride };
}

// Dates at this seam are ISO calendar dates, already resolved in the household time zone.
export function schedule(input: ScheduleInput): Schedule {
  const builtIn = Object.fromEntries(taskTypes.map(type => [type, scheduleTask(input, type, taskRules[type], intervalFor(input, type))])) as Record<TaskType, Task>;
  const customTasks = Object.fromEntries((input.customTasks ?? []).map(task => {
    const rules: TaskRules = { label: task.id, action: task.id, defaultInterval: { value: task.intervalDays, source: 'override', reason: 'Custom task interval.' }, minDays: 1, maxDays: 365, factors: [], anchor: 'added_plus_interval' };
    const addedOn = task.createdOn > input.addedOn ? task.createdOn : input.addedOn;
    const basedOn: BasedOn[] = [{ input: 'customTask.intervalDays', value: task.intervalDays, source: 'override', adjustment: 'Custom task uses a fixed interval.' }];
    const anchorBasis: BasedOn = { input: 'customTask.anchor', value: { addedOn: input.addedOn, createdOn: task.createdOn, anchor: addedOn }, source: 'history', adjustment: 'First due date counts from the later of plant addition and task creation.' };
    return [task.id, scheduleTask({ ...input, addedOn }, task.id, rules, { intervalDays: task.intervalDays, basedOn, overridden: false, anchorBasis })];
  }));
  return { ...builtIn, customTasks };
}

// Built-in and custom tasks share anchoring, completion and postponement handling.
function scheduleTask(input: ScheduleInput, taskType: string, rules: TaskRules, computed: { intervalDays: number; basedOn: BasedOn[]; overridden: boolean; anchorBasis?: BasedOn }): Task {
  const { intervalDays, basedOn, overridden } = computed;
  const applicability = rules.applicability?.(input);
  if (applicability) basedOn.push(...applicability.basedOn);
  basedOn.push({ input: 'weather', value: input.weather ?? 'not set', source: 'weather',
    adjustment: input.weather ? 'Weather snapshot supplied; weather adjustments arrive in the weather module.' : 'not set; no adjustment' });
  if (applicability && !applicability.applicable) return { status: 'not_applicable', basedOn };
  const completion = input.history[taskType]?.lastCompletedOn;
  const anchor = completion ?? input.addedOn;
  basedOn.push(!completion && computed.anchorBasis ? computed.anchorBasis : { input: completion ? `history.${taskType}.lastCompletedOn` : 'addedOn', value: anchor, source: 'history',
    adjustment: completion ? `counted from last completion on ${completion}` : `counted from the date the plant was added, ${input.addedOn}` });
  // UTC here represents plain calendar days, not instants in the household time zone.
  let due = new Date(anchor + 'T00:00:00Z');
  if (typeof rules.anchor === 'function' && !overridden) {
    const result = rules.anchor(input, completion);
    basedOn.push(...result.basedOn);
    due = new Date(result.dueOn + 'T00:00:00Z');
  } else if (completion || rules.anchor !== 'added_date') due.setUTCDate(due.getUTCDate() + intervalDays);
  if (rules.season) {
    const { firstMonth, lastMonth, ruleId } = rules.season;
    const month = due.getUTCMonth() + 1;
    const shifted = month < firstMonth || month > lastMonth;
    if (shifted) due = new Date(Date.UTC(due.getUTCFullYear() + (month > lastMonth ? 1 : 0), firstMonth - 1, 1));
    basedOn.push({ input: 'season', value: { firstMonth, lastMonth }, source: 'rule', ruleId,
      adjustment: shifted ? `Outside the season; moved to ${due.toISOString().slice(0, 10)}.` : 'Due date is inside the active season; no adjustment.' });
  }
  // The adapter supplies only the latest effective postponement, after the last completion.
  const history = input.history[taskType];
  const postponement = ([['snoozedUntil', history?.snoozedUntil, 2], ['skippedOn', history?.skippedOn, intervalDays]] as const)
    .filter((entry): entry is typeof entry & { 1: string } => !!entry[1] && (!completion || entry[1] >= completion))
    .sort((a, b) => b[1].localeCompare(a[1]))[0];
  if (postponement) {
    const [field, date, days] = postponement;
    due = new Date(date + 'T00:00:00Z');
    due.setUTCDate(due.getUTCDate() + days);
    basedOn.push({ input: `history.${taskType}.${field}`, value: date, source: 'history',
      adjustment: `${field === 'snoozedUntil' ? 'Snoozed for 2 days' : 'Skipped until the next interval'} from ${date}.` });
  }
  const dueOn = due.toISOString().slice(0, 10);
  const lateDays = Math.max(0, Math.round((Date.parse(input.today + 'T00:00:00Z') - due.getTime()) / 86_400_000));
  return { status: 'due', dueOn, intervalDays, lateDays, basedOn };
}

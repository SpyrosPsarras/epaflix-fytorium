import { config } from './config';

// Intl owns time-zone and DST conversion; callers receive plain calendar dates.
export function calendarDate(at: Date | string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: config().householdTimezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(at));
}

export function isCalendarDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= '0001-01-01'
    && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
}

let fixed: number | undefined;

export function now(): Date {
  return new Date(fixed ?? Date.now());
}

export function controlClock(command: unknown): Date {
  if (!config().testClockEnabled) throw new Error('Test clock is disabled');
  if (!command || typeof command !== 'object' || !('action' in command)) throw new Error('Expected a clock action');
  let next: number | undefined;
  if (command.action === 'reset') {
    next = undefined;
  } else if (command.action === 'set' && 'at' in command && typeof command.at === 'string') {
    next = Date.parse(command.at);
  } else if (command.action === 'advance' && 'milliseconds' in command && typeof command.milliseconds === 'number') {
    next = now().getTime() + command.milliseconds;
  } else {
    throw new Error('Expected set with at, advance with milliseconds, or reset');
  }
  if (next !== undefined && !Number.isFinite(new Date(next).getTime())) throw new Error('Invalid clock time');
  fixed = next;
  return now();
}

import { config } from './config';

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

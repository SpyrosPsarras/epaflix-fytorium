import { expect, test } from 'vitest';
import { schedule, type ScheduleInput, type TaskHistory } from '../../src/lib/schedule/engine';

const input: ScheduleInput = { careProfile: {}, site: {}, pot: {}, plant: {}, history: {}, customIntervals: {}, addedOn: '2026-05-01', today: '2026-05-20' };

test('a custom task starts after the later of plant addition and task creation', () => {
  const result = schedule({ ...input, customTasks: [{ id: 'inspect', intervalDays: 7, createdOn: '2026-05-10' }] });
  expect(result.customTasks.inspect).toMatchObject({ dueOn: '2026-05-17', intervalDays: 7, lateDays: 3 });
});

const rows: [string, string, number, TaskHistory, string][] = [
  ['plant added later', '2026-04-01', 7, {}, '2026-05-08'],
  ['completion', '2026-05-10', 7, { lastCompletedOn: '2026-05-18' }, '2026-05-25'],
  ['snooze after completion', '2026-05-10', 7, { lastCompletedOn: '2026-05-18', snoozedUntil: '2026-05-20' }, '2026-05-22'],
  ['skip', '2026-05-10', 7, { skippedOn: '2026-05-20' }, '2026-05-27'],
  ['undo completion', '2026-05-10', 7, {}, '2026-05-17'],
  ['undo snooze to completion', '2026-05-10', 7, { lastCompletedOn: '2026-05-18' }, '2026-05-25'],
  ['interval edit', '2026-05-10', 3, { lastCompletedOn: '2026-05-18' }, '2026-05-21'],
];
test.each(rows)('%s uses the generic interval/history path', (_name, createdOn, intervalDays, history, dueOn) => {
  expect(schedule({ ...input, customTasks: [{ id: 'inspect', createdOn, intervalDays }], history: { inspect: history } }).customTasks.inspect).toMatchObject({ dueOn, intervalDays });
});

test('one site task schedules two plants with independent completion histories', () => {
  const customTasks = [{ id: 'site-inspect', intervalDays: 7, createdOn: '2026-05-10' }];
  expect(schedule({ ...input, customTasks, history: { 'site-inspect': { lastCompletedOn: '2026-05-18' } } }).customTasks['site-inspect']).toMatchObject({ dueOn: '2026-05-25' });
  expect(schedule({ ...input, customTasks, addedOn: '2026-05-12' }).customTasks['site-inspect']).toMatchObject({ dueOn: '2026-05-19' });
});

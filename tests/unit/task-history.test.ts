import { expect, test } from 'vitest';
import { schedule, type ScheduleInput, type TaskType } from '../../src/lib/schedule/engine';

const input: ScheduleInput = { careProfile: {}, site: { location: 'indoor', humidity: 'normal' }, pot: {}, plant: {}, history: {}, customIntervals: {}, addedOn: '2026-05-01', today: '2026-05-03' };
const skips: [TaskType, string][] = [
  ['water', '2026-05-10'], ['fertilize', '2026-05-17'], ['mist', '2026-05-06'], ['clean_leaves', '2026-06-02'],
  ['remove_dead', '2026-05-17'], ['cut_back', '2027-05-03'], ['repot', '2028-05-02'],
];
test.each(skips)('the generic history step snoozes or skips %s without changing its interval', (type, skipDue) => {
  const unchanged = schedule(input)[type];
  expect(schedule({ ...input, history: { [type]: { snoozedUntil: '2026-05-03' } } })[type]).toMatchObject({ dueOn: '2026-05-05', intervalDays: unchanged.status === 'due' ? unchanged.intervalDays : undefined });
  expect(schedule({ ...input, history: { [type]: { skippedOn: '2026-05-03' } } })[type]).toMatchObject({ dueOn: skipDue });
});
test.each([
  ['undo snooze to completion', { lastCompletedOn: '2026-05-01' }, '2026-05-08'],
  ['undo skip to earlier snooze', { snoozedUntil: '2026-05-02' }, '2026-05-04'],
  ['undo snooze to earlier skip', { skippedOn: '2026-05-02' }, '2026-05-09'],
] as const)('%s restores the literal date %s', (_name, water, dueOn) => {
  expect(schedule({ ...input, history: { water } }).water).toMatchObject({ dueOn });
});
test('skip uses a custom interval and snooze is exactly two days even outside a seasonal window', () => {
  expect(schedule({ ...input, customIntervals: { water: 5 }, history: { water: { skippedOn: '2026-05-03' } } }).water).toMatchObject({ intervalDays: 5, dueOn: '2026-05-08' });
  expect(schedule({ ...input, history: { fertilize: { snoozedUntil: '2026-12-30' } } }).fertilize).toMatchObject({ dueOn: '2027-01-01' });
});

import { reconcileStart } from '../session/start';

const run = (signedIn: boolean) => {
  const calls: string[] = [];
  reconcileStart({
    signedIn,
    care: { restore: () => calls.push('restore'), wipe: () => calls.push('wipe') },
    background: { start: () => calls.push('schedule'), stop: () => calls.push('cancel') },
  });
  return calls;
};

test('a signed-in start keeps the cache and makes sure the 6-hour read is scheduled', () => {
  expect(run(true)).toEqual(['restore', 'schedule']);
});

test('a signed-out start wipes what an interrupted logout left and cancels the schedule', () => {
  expect(run(false)).toEqual(['wipe', 'cancel']);
});

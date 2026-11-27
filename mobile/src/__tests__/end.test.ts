import { endSession } from '../session/end';

test('a session end stops the background refresh and wipes the cache before the screen changes', async () => {
  const calls: string[] = [];
  await endSession({
    push: { sessionEnded: async () => { calls.push('push'); } },
    background: { stop: () => calls.push('background stopped') },
    care: { wipe: () => calls.push('cache wiped') },
    diagnostics: { clear: () => calls.push('diagnostics cleared'), record: (event) => calls.push(event) },
    session: { sessionChanged: () => calls.push('screen') },
  });
  expect(calls).toEqual(['push', 'background stopped', 'cache wiped', 'diagnostics cleared', 'SESSION_ENDED', 'screen']);
});

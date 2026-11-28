import { installCrashHandler } from '../crash';

test('a crash records only its event and never passes the original error on', () => {
  const forwarded: [unknown, boolean | undefined][] = [];
  let installed: (error: unknown, fatal?: boolean) => void = () => undefined;
  let recorded = 0;
  installCrashHandler({
    getGlobalHandler: () => (error, fatal) => { forwarded.push([error, fatal]); },
    setGlobalHandler: (handler) => { installed = handler; },
  }, () => { recorded += 1; });
  const secret = 'Authorization failed for synthetic-secret ' + 'x'.repeat(8);
  installed(Object.assign(new Error(secret), { cause: secret }), true);
  installed(new Error(secret), false);
  expect(recorded).toBe(1);
  expect(forwarded.map(([, fatal]) => fatal)).toEqual([true, false]);
  for (const [error] of forwarded) {
    expect(error).toBeInstanceOf(Error);
    expect(JSON.stringify([(error as Error).message, (error as Error).stack, (error as Error).cause])).not.toContain('synthetic-secret');
  }
});

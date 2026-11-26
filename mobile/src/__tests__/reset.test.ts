import { readyToRegister, type ResetDeps } from '../push/reset';

function deps(failures: number) {
  const flags = new Map<string, boolean>([['push_reset_pending', true]]);
  let calls = 0;
  const value: ResetDeps = {
    getFlag: (name) => flags.get(name) ?? false,
    setFlag: (name, on) => { flags.set(name, on); },
    deleteToken: async () => { calls += 1; if (calls <= failures) throw new Error('offline'); },
  };
  return { value, flags, calls: () => calls };
}

test('registration waits until deleting the old token succeeds', async () => {
  const d = deps(1);
  await expect(readyToRegister(d.value)).resolves.toBe(false);
  expect(d.flags.get('push_reset_pending')).toBe(true);
  await expect(readyToRegister(d.value)).resolves.toBe(true);
  expect(d.flags.get('push_reset_pending')).toBe(false);
  expect(d.calls()).toBe(2);
});

test('no deletion when no reset is pending', async () => {
  const d = deps(0);
  d.flags.set('push_reset_pending', false);
  await expect(readyToRegister(d.value)).resolves.toBe(true);
  expect(d.calls()).toBe(0);
});

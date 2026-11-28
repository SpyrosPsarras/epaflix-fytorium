import type { RawResponse } from '../../modules/fytorium-native';
import { createPushRegistration } from '../push/registration';
import type { PushTokens } from '../push/tokens';

function harness({ deleteFailures = 0, replies = [] as number[], ios = false, flagsBroken = false } = {}) {
  let unregistered = 0;
  const flags = new Map<string, boolean>([['push_reset_pending', true]]);
  let token = 'synthetic-old';
  let failures = deleteFailures;
  let session: number | null = 1;
  const refresh = new Set<(token: string) => void>();
  const posts: string[] = [];
  const gates: (() => void)[] = [];
  let gated = false;
  let onGet: (() => void) | null = null;
  const tokens: PushTokens = {
    get: async () => { onGet?.(); return token; },
    delete: async () => {
      if (failures > 0) { failures -= 1; throw new Error('offline'); }
      token = 'synthetic-' + (posts.length + 1);
    },
    onRefresh: (listener) => { refresh.add(listener); return () => refresh.delete(listener); },
    registerDevice: async () => undefined,
    unregisterDevice: async () => { unregistered += 1; },
  };
  const api = {
    request: async (_path: string, options?: { body?: unknown }): Promise<RawResponse> => {
      posts.push((options?.body as { token: string }).token);
      if (gated) await new Promise<void>((resolve) => gates.push(resolve));
      return { status: replies.shift() ?? 201, headers: {}, body: '{}' };
    },
  };
  const registration = createPushRegistration({
    api: api as never, tokens, ios, ready: () => true, session: () => session,
    flags: {
      getFlag: (name) => flags.get(name) ?? false,
      setFlag: (name, value) => { flags.set(name, value); },
      setFlags: (values) => { if (flagsBroken) return false; Object.entries(values).forEach(([name, value]) => flags.set(name, value!)); return true; },
    },
  });
  return {
    registration, flags, posts,
    rotate(next: string) { token = next; refresh.forEach((listener) => listener(next)); },
    gate(on: boolean) { gated = on; },
    open: () => gates.splice(0).forEach((resolve) => resolve()),
    unregistered: () => unregistered,
    endSession() { session = null; },
    switchSession(next: number) { session = next; },
    duringTokenRead(action: () => void) { onGet = action; },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('nothing is posted until the old token is deleted', async () => {
  const h = harness({ deleteFailures: 1 });
  await h.registration.sync();
  expect(h.posts).toEqual([]);
  expect(h.flags.get('push_ready')).toBeFalsy();
  await h.registration.sync();
  expect(h.posts).toHaveLength(1);
  expect(h.posts[0]).not.toBe('synthetic-old');
  expect(h.flags.get('push_ready')).toBe(true);
});

test('a first post that gets a 503 is retried on the next foreground without a new login', async () => {
  const h = harness({ replies: [503] });
  await h.registration.sync();
  expect(h.flags.get('push_ready')).toBeFalsy();
  await h.registration.sync();
  expect(h.posts).toHaveLength(2);
  expect(h.flags.get('push_ready')).toBe(true);
});

test('a delayed post of the old token cannot overwrite a rotated token', async () => {
  const h = harness();
  h.flags.set('push_reset_pending', false);
  h.gate(true);
  const first = h.registration.sync();
  await settle();
  expect(h.posts).toEqual(['synthetic-old']);
  h.rotate('synthetic-rotated');
  h.gate(false);
  h.open();
  await first;
  expect(h.posts).toEqual(['synthetic-old', 'synthetic-rotated']);
});

test('a registration that finishes after the session ended does not open the push gate', async () => {
  const h = harness();
  h.flags.set('push_reset_pending', false);
  h.gate(true);
  const run = h.registration.sync();
  await settle();
  h.endSession();
  h.registration.closeGate();
  await h.registration.sessionEnded();
  h.open();
  await run;
  expect(h.flags.get('push_ready')).toBe(false);
});

test('logged out, the old token is still deleted but nothing registers', async () => {
  const h = harness();
  h.endSession();
  await h.registration.sync();
  expect(h.flags.get('push_reset_pending')).toBe(false);
  expect(h.posts).toEqual([]);
});

test('a member change while the token is read posts nothing for the old session', async () => {
  const h = harness();
  h.flags.set('push_reset_pending', false);
  h.duringTokenRead(() => h.switchSession(2));
  await h.registration.sync();
  expect(h.posts).toEqual([]);
  expect(h.flags.get('push_ready')).toBeFalsy();
});

test('a session end closes the gate and starts deleting the token at once', async () => {
  const h = harness();
  h.flags.set('push_reset_pending', false);
  h.flags.set('push_ready', true);
  h.endSession();
  h.registration.closeGate();
  await h.registration.sessionEnded();
  await settle();
  expect(h.flags.get('push_ready')).toBe(false);
  expect(h.flags.get('push_reset_pending')).toBe(false);
});

test('closing the gate also persists the wipe marker in the same write', () => {
  const h = harness();
  h.flags.set('push_ready', true);
  h.registration.closeGate();
  expect(Object.fromEntries(h.flags)).toMatchObject({ push_ready: false, push_reset_pending: true, session_wipe_pending: true });
});

test('on iOS an unfinished reset unregisters from APNs again before deleting the token', async () => {
  const h = harness({ ios: true, deleteFailures: 1 });
  h.endSession();
  await h.registration.sync();
  expect(h.unregistered()).toBe(1);
  await h.registration.sync();
  expect(h.unregistered()).toBe(2);
  expect(h.flags.get('push_reset_pending')).toBe(false);
});

test('when the session-end flags cannot be saved, the old token is still deleted before the next registration', async () => {
  const h = harness({ flagsBroken: true });
  h.flags.set('push_reset_pending', false);
  h.registration.closeGate();
  expect(h.flags.get('push_reset_pending')).toBe(false);
  await h.registration.sync();
  expect(h.posts).toHaveLength(1);
  expect(h.posts[0]).not.toBe('synthetic-old');
});

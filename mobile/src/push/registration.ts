import type { Flag } from '../../modules/fytorium-native';
import type { Api } from '../api/client';
import type { PushTokens } from './tokens';
import { readyToRegister } from './reset';

type Deps = {
  api: Pick<Api, 'request'>;
  tokens: PushTokens;
  flags: { getFlag(name: Flag): boolean; setFlag(name: Flag, value: boolean): void; setFlags(values: Partial<Record<Flag, boolean>>): boolean };
  // First-run cleanup finished; nothing touches tokens before it.
  ready(): boolean;
  // The current session number, or null when logged out; see Credentials.session.
  session(): number | null;
  ios: boolean;
};

// Push token lifecycle from the migration design: reset, register, re-register.
export function createPushRegistration(deps: Deps) {
  let running: Promise<void> | null = null;
  let again = false;
  // A reset this process asked for, even if its flag never reached storage: the old token is still deleted first.
  let resetOutstanding = false;
  const resetPending = () => resetOutstanding || deps.flags.getFlag('push_reset_pending');
  const reset = {
    getFlag: (name: Flag) => (name === 'push_reset_pending' ? resetPending() : deps.flags.getFlag(name)),
    setFlag: (name: Flag, value: boolean) => {
      deps.flags.setFlag(name, value);
      if (name === 'push_reset_pending' && !value) resetOutstanding = false;
    },
  };

  async function once() {
    if (!deps.ready()) return;
    // An interrupted session end may have missed APNs unregistration; it is repeated while the reset is pending.
    if (deps.ios && resetPending()) await deps.tokens.unregisterDevice().catch(() => undefined);
    // The previous session's token is deleted whenever online, logged in or not.
    if (!(await readyToRegister({ ...reset, deleteToken: deps.tokens.delete }))) return;
    const session = deps.session();
    if (session === null) return;
    if (deps.ios) await deps.tokens.registerDevice();
    const token = await deps.tokens.get();
    // Another session may have started, or ended and left a reset pending, while the token was read.
    if (deps.session() !== session || resetPending()) return;
    const response = await deps.api.request('/api/notifications/subscriptions', { method: 'POST', body: { transport: 'fcm', token } });
    // A result that arrives after the session ended belongs to nobody.
    if (deps.session() !== session || response.status < 200 || response.status > 299) return;
    deps.flags.setFlag('push_ready', true);
  }

  // One run at a time per process; a request during a run (a new token, a foreground) runs once more afterwards.
  function sync(): Promise<void> {
    if (running) {
      again = true;
      return running;
    }
    running = (async () => {
      do {
        again = false;
        try { await once(); } catch { /* the next foreground or reconnect retries */ }
      } while (again);
    })().finally(() => { running = null; });
    return running;
  }

  // Session end, online or not: stop showing pushes, delete the token before any new registration, stop APNs delivery.
  // Synchronous and first in the wipe, in one commit with the wipe marker: from here on N4 drops every push, the token is
  // deleted before any reuse and a restart refuses the stored credentials. iOS stops APNs delivery before the delete.
  function closeGate() {
    resetOutstanding = true;
    deps.flags.setFlags({ push_ready: false, push_reset_pending: true, session_wipe_pending: true });
    if (deps.ios) void deps.tokens.unregisterDevice().catch(() => undefined);
  }

  async function sessionEnded() {
    void sync();
  }

  const stopRefresh = deps.tokens.onRefresh(() => { void sync(); });
  return { sync, closeGate, sessionEnded, close: stopRefresh };
}

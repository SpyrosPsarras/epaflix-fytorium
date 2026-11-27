import type { Flag } from '../../modules/fytorium-native';
import type { Api } from '../api/client';
import type { PushTokens } from './tokens';
import { readyToRegister } from './reset';

type Deps = {
  api: Pick<Api, 'request'>;
  tokens: PushTokens;
  flags: { getFlag(name: Flag): boolean; setFlag(name: Flag, value: boolean): void };
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

  async function once() {
    if (!deps.ready()) return;
    // The previous session's token is deleted whenever online, logged in or not.
    if (!(await readyToRegister({ getFlag: deps.flags.getFlag, setFlag: deps.flags.setFlag, deleteToken: deps.tokens.delete }))) return;
    const session = deps.session();
    if (session === null) return;
    if (deps.ios) await deps.tokens.registerDevice();
    const token = await deps.tokens.get();
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
  async function sessionEnded() {
    deps.flags.setFlag('push_ready', false);
    deps.flags.setFlag('push_reset_pending', true);
    try { await deps.tokens.unregisterDevice(); } catch { /* best effort; the reset flag still blocks reuse */ }
  }

  const stopRefresh = deps.tokens.onRefresh(() => { void sync(); });
  return { sync, sessionEnded, close: stopRefresh };
}

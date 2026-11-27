import type { Api } from '../api/client';
import { NetworkError } from '../api/client';
import { requiresUpdate } from './version';
import * as diagnostics from '../diagnostics';

export type SessionState = {
  loggedIn: boolean;
  busy: boolean;
  message: string | null;
  updateRequired: boolean;
  // The server's minimum from /api/meta, for the update stop.
  minAppVersion: string | null;
  serverVersion: string | null;
};

export function loginError(code: string): string {
  switch (code) {
    case 'not_member': return "This account isn't in a household yet.";
    case 'access_denied': return 'Login was cancelled.';
    case 'server_error': return "Fytorium couldn't finish the login. Try again.";
    default: return "Login didn't work. Try again.";
  }
}

const OFFLINE = "Can't reach Fytorium. Check the connection and try again.";

type Deps = { api: Api; loggedIn(): boolean; appVersion: string; ready(): boolean; onLoggedIn(): void };

export function createSession(deps: Deps) {
  let state: SessionState = {
    loggedIn: deps.loggedIn(), busy: false, message: null, updateRequired: false, minAppVersion: null, serverVersion: null,
  };
  const listeners = new Set<() => void>();
  const queued: string[] = [];
  const handled = new Set<string>();
  let recheckWaiting = false;

  function set(patch: Partial<SessionState>) {
    state = { ...state, ...patch, loggedIn: deps.loggedIn() };
    listeners.forEach((listener) => listener());
  }

  async function checkVersionNow() {
    const response = await deps.api.request('/api/meta');
    let minimum: string | null = null;
    let server: string | null = null;
    try {
      const json = JSON.parse(response.body);
      if (typeof json?.minAppVersion === 'string' && json.minAppVersion.trim()) minimum = json.minAppVersion;
      if (typeof json?.serverVersion === 'string') server = json.serverVersion;
    } catch { /* a 426 or 404 has no usable body */ }
    let required: boolean | null = null;
    if (response.status === 426) required = true;
    else if (response.status >= 200 && response.status < 300) required = requiresUpdate(response.status, response.body, deps.appVersion);
    if (required !== null) {
      set({ updateRequired: required, minAppVersion: minimum ?? state.minAppVersion, serverVersion: server ?? state.serverVersion });
    }
  }

  function work(action: () => Promise<void>, offline = OFFLINE) {
    if (state.busy) return;
    set({ busy: true, message: null });
    void (async () => {
      try {
        await action();
      } catch (error) {
        diagnostics.record('NETWORK_UNAVAILABLE');
        set({ message: error instanceof NetworkError ? offline : OFFLINE });
      } finally {
        set({ busy: false });
        if (recheckWaiting) { recheckWaiting = false; recheck(); }
        while (!state.busy && queued.length) handleRedirect(queued.shift()!);
      }
    })();
  }

  function handleRedirect(redirect: string) {
    if (!deps.ready() || deps.loggedIn() || handled.has(redirect)) return;
    if (state.busy) { queued.push(redirect); return; }
    handled.add(redirect);
    work(async () => {
      const error = await deps.api.completeLogin(redirect);
      if (error) {
        diagnostics.record('LOGIN_REJECTED');
        set({ message: loginError(error) });
      } else {
        set({});
        deps.onLoggedIn();
      }
      await checkVersionNow();
    });
  }

  function recheck() {
    if (state.busy) recheckWaiting = true;
    else work(checkVersionNow);
  }

  return {
    get: () => state,
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    start: () => work(checkVersionNow),
    // Login waits for first-run cleanup, so a retried cleanup never wipes a new session.
    async loginUrl(): Promise<string | null> {
      if (!deps.ready()) {
        set({ message: "Fytorium couldn't finish setting up. Close it and open it again." });
        return null;
      }
      return deps.api.loginUrl();
    },
    loginLaunchFailed() {
      void deps.api.cancelLogin();
      diagnostics.record('LOGIN_REJECTED');
      set({ message: "Couldn't open login. Install a browser and try again." });
    },
    handleRedirect,
    // The stop goes with the session.
    logout() {
      set({ updateRequired: false });
      work(() => deps.api.logout());
    },
    recheck,
    // A 426 from any request: read the minimum again and show the stop, once.
    updateRequired() {
      if (!state.updateRequired) recheck();
    },
    checkAgain(checkedAt: () => string) {
      work(async () => {
        await checkVersionNow();
        if (state.updateRequired) set({ message: `Still needs ${state.minAppVersion ?? 'a newer Fytorium'} or newer. Checked ${checkedAt()}.` });
      }, 'Checking needs a connection.');
    },
    copyDiagnostics(appVersion: string): string {
      set({ message: 'Diagnostics copied' });
      return diagnostics.copyText(appVersion);
    },
    sessionChanged: () => set({}),
  };
}

export type Session = ReturnType<typeof createSession>;

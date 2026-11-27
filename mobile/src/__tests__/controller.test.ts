import { NetworkError, type Api } from '../api/client';
import { createSession } from '../session/controller';

type Meta = { status: number; body: string } | 'offline';

function harness({ ready = true } = {}) {
  let loggedIn = false;
  const logins: string[] = [];
  const metas: Meta[] = [];
  let release: (() => void) | null = null;
  const api = {
    request: async () => {
      const next = metas.shift() ?? { status: 200, body: '{"minAppVersion":null}' };
      if (next === 'offline') throw new NetworkError("Can't reach Fytorium.");
      return { ...next, headers: {} };
    },
    completeLogin: async (redirect: string) => {
      logins.push(redirect);
      if (release) await new Promise<void>((resolve) => { release = resolve; });
      loggedIn = true;
      return null;
    },
    cancelLogin: async () => undefined,
    loginUrl: async () => 'https://fytorium.test/auth/login',
    logout: async () => { loggedIn = false; },
  } as unknown as Api;
  const session = createSession({ api, appVersion: '2.0.0', loggedIn: () => loggedIn, ready: () => ready, onLoggedIn: () => undefined });
  return {
    session, logins, metas,
    holdLogin() { release = () => undefined; },
    releaseLogin() { const done = release; release = null; done?.(); },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test('the same redirect from two sources completes once', async () => {
  const h = harness();
  h.session.handleRedirect('com.epaflix.fytorium:/oauth2redirect?code=a&state=s');
  h.session.handleRedirect('com.epaflix.fytorium:/oauth2redirect?code=a&state=s');
  await settle();
  expect(h.logins).toHaveLength(1);
  expect(h.session.get().loggedIn).toBe(true);
});

test('a redirect that arrives during a version check waits its turn', async () => {
  const h = harness();
  h.session.start();
  h.session.handleRedirect('com.epaflix.fytorium:/oauth2redirect?code=b&state=s');
  expect(h.logins).toHaveLength(0);
  await settle();
  await settle();
  expect(h.logins).toHaveLength(1);
});

test('a redirect before first-run cleanup finished is ignored, and login says why', async () => {
  const h = harness({ ready: false });
  h.session.handleRedirect('com.epaflix.fytorium:/oauth2redirect?code=c&state=s');
  await settle();
  expect(h.logins).toHaveLength(0);
  await expect(h.session.loginUrl()).resolves.toBeNull();
  expect(h.session.get().message).toMatch(/couldn't finish setting up/);
});

test('a 426 from any request shows the stop with the minimum from /api/meta', async () => {
  const h = harness();
  h.metas.push({ status: 200, body: '{"minAppVersion":"3.1.0","serverVersion":"synthetic"}' });
  h.session.updateRequired();
  await settle();
  expect(h.session.get()).toMatchObject({ updateRequired: true, minAppVersion: '3.1.0', serverVersion: 'synthetic' });
});

test('Check again offline says it needs a connection and keeps the stop', async () => {
  const h = harness();
  h.metas.push({ status: 426, body: '' });
  h.session.start();
  await settle();
  expect(h.session.get().updateRequired).toBe(true);
  h.metas.push('offline');
  h.session.checkAgain(() => '12:00:00');
  await settle();
  expect(h.session.get()).toMatchObject({ updateRequired: true, message: 'Checking needs a connection.' });
});

test('logout drops the stop', async () => {
  const h = harness();
  h.metas.push({ status: 426, body: '' });
  h.session.start();
  await settle();
  h.session.logout();
  expect(h.session.get().updateRequired).toBe(false);
});

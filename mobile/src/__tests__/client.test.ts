import type { RawResponse, RequestOptions } from '../../modules/fytorium-native';
import { createApi, NetworkError, type Transport } from '../api/client';
import { Credentials, WipeError } from '../session/credentials';
import { challenge, REDIRECT_URI } from '../session/pkce';
import { requiresUpdate } from '../session/version';

const pair = JSON.stringify({ access_token: 'synthetic-new', refresh_token: 'synthetic-next', token_type: 'Bearer', expires_in: 3600, device_id: 'test' });
type Reply = Partial<RawResponse> | 'drop';

function fakeServer() {
  const requests: RequestOptions[] = [];
  const queue: Reply[] = [];
  let dispatch: ((request: RequestOptions) => Promise<Reply> | Reply) | null = null;
  const transport: Transport = async (request) => {
    requests.push(request);
    const reply = dispatch ? await dispatch(request) : queue.shift();
    if (!reply) throw new Error('No reply queued');
    if (reply === 'drop') throw new Error('Connection dropped');
    return { status: 200, headers: {}, body: '', ...reply };
  };
  return { requests, transport, enqueue: (...replies: Reply[]) => queue.push(...replies), dispatch: (fn: typeof dispatch) => { dispatch = fn; } };
}

function marker(initial = false) {
  let value = initial;
  let broken = false;
  return {
    get: () => value,
    // Like the iOS flag store: a write that cannot reach disk reports false and changes nothing.
    set: (next: boolean) => { if (broken) return false; value = next; return true; },
    break: (on: boolean) => { broken = on; },
  };
}

// Like Android SharedPreferences: a failed commit still changes the value in memory.
function androidMarker() {
  let value = false;
  let broken = false;
  return {
    get: () => value,
    set: (next: boolean) => { value = next; return !broken; },
    break: (on: boolean) => { broken = on; },
  };
}

async function setup(tokens?: { access: string; refresh: string }) {
  const server = fakeServer();
  const wipe = marker();
  const credentials = new Credentials(true, wipe);
  await credentials.clear();
  if (tokens) await credentials.setTokens(tokens);
  let lost = 0;
  const updates: (string | null)[] = [];
  const events: string[] = [];
  let language = 'el-GR';
  const api = createApi({
    baseUrl: 'https://fytorium.test', credentials, transport: server.transport, appVersion: '2.0.0', platform: 'android',
    language: () => language, deviceName: () => 'Synthetic phone',
    beforeWipe: () => { events.push(`gate closed, wipe marker ${wipe.get()}`); },
    onSessionLost: () => { lost += 1; events.push(`session lost, wipe marker ${wipe.get()}`); },
    onUpdateRequired: (minimum) => { updates.push(minimum); },
  });
  return { server, credentials, api, wipe, events, lost: () => lost, updates, setLanguage: (value: string) => { language = value; } };
}

const json = (request: RequestOptions) => JSON.parse(request.body ?? '{}');
const stateOf = (credentials: Credentials) => credentials.pending()!.state;

test('API requests send the current phone language and app version', async () => {
  const { server, api, credentials, setLanguage } = await setup();
  for (const method of ['GET', 'POST', 'PATCH'] as const) {
    server.enqueue({ body: '{}' });
    await api.request('/api/meta', { method });
    expect(server.requests.at(-1)!.headers['Accept-Language']).toBe('el-GR');
    expect(server.requests.at(-1)!.headers['X-App-Version']).toBe('2.0.0');
  }
  await api.loginUrl();
  server.enqueue({ body: pair });
  expect(await api.completeLogin(`${REDIRECT_URI}?code=synthetic-code&state=${stateOf(credentials)}`)).toBeNull();
  expect(server.requests.at(-1)!.url).toBe('https://fytorium.test/api/app/token');
  expect(server.requests.at(-1)!.headers['Accept-Language']).toBe('el-GR');
  setLanguage('en-GB');
  server.enqueue({ body: '{}' });
  await api.request('/api/meta');
  expect(server.requests.at(-1)!.headers['Accept-Language']).toBe('en-GB');
});

test('login matches the server contract and consumes the pending attempt', async () => {
  const { server, api, credentials } = await setup();
  const url = new URL(await api.loginUrl());
  expect(url.pathname).toBe('/auth/login');
  expect(url.searchParams.get('app')).toBe('android');
  expect(url.searchParams.get('redirect_uri')).toBe(REDIRECT_URI);
  expect(url.searchParams.get('code_challenge_method')).toBe('S256');
  expect(url.searchParams.get('code_challenge')).toBe(await challenge(credentials.pending()!.verifier));
  const state = stateOf(credentials);
  server.enqueue({ body: pair });
  expect(await api.completeLogin(`${REDIRECT_URI}?code=synthetic-code&state=${state}`)).toBeNull();
  const request = server.requests[0];
  expect(request.tokenEndpoint).toBe(true);
  expect(request.headers['Content-Type']).toMatch(/^application\/json/);
  expect(request.headers.Authorization).toBeUndefined();
  expect(json(request)).toMatchObject({ grant_type: 'authorization_code', code: 'synthetic-code', device_name: 'Synthetic phone' });
  expect(await challenge(json(request).code_verifier)).toBe(url.searchParams.get('code_challenge'));
  expect(credentials.pending()).toBeNull();
  expect(await api.completeLogin(`${REDIRECT_URI}?code=synthetic-code&state=${state}`)).toBe('invalid');
  expect(credentials.tokens()!.access).toBe('synthetic-new');
  expect(server.requests).toHaveLength(1);
});

test('a refused redirect never reaches the server and keeps the attempt', async () => {
  const { server, api, credentials } = await setup();
  await api.loginUrl();
  expect(await api.completeLogin(`${REDIRECT_URI}?code=x&state=wrong`)).toBe('invalid');
  expect(server.requests).toHaveLength(0);
  expect(credentials.pending()).not.toBeNull();
});

test('the server reason for a refused login ends the attempt', async () => {
  const { server, api, credentials } = await setup();
  await api.loginUrl();
  expect(await api.completeLogin(`${REDIRECT_URI}?error=not_member&state=${stateOf(credentials)}`)).toBe('not_member');
  expect(server.requests).toHaveLength(0);
  expect(credentials.pending()).toBeNull();
});

test('a 401 refreshes once and rotates the tokens', async () => {
  const { server, api, credentials } = await setup({ access: 'synthetic-old', refresh: 'synthetic-refresh' });
  server.enqueue({ status: 401 }, { body: pair }, { body: '{}' });
  expect((await api.request('/api/meta')).status).toBe(200);
  expect(server.requests[0].headers.Authorization).toBe('Bearer synthetic-old');
  expect(server.requests[1].tokenEndpoint).toBe(true);
  expect(json(server.requests[1])).toEqual({ grant_type: 'refresh_token', refresh_token: 'synthetic-refresh' });
  expect(server.requests[2].headers.Authorization).toBe('Bearer synthetic-new');
  expect(credentials.tokens()!.refresh).toBe('synthetic-next');
});

test.each([[400, 'invalid_grant'], [401, 'invalid_token']])('only refused refresh credentials end the session (%i %s)', async (status, error) => {
  const { server, api, credentials, lost } = await setup({ access: 'synthetic-old', refresh: 'synthetic-refresh' });
  server.enqueue({ status: 401, body: 'secret detail' }, { status, body: JSON.stringify({ error }) });
  const response = await api.request('/api/meta');
  expect(response.status).toBe(401);
  expect(response.body).toBe('');
  expect(credentials.tokens()).toBeNull();
  expect(lost()).toBe(1);
  expect(server.requests).toHaveLength(2);
});

test('a second 401 after refresh keeps the rotated tokens', async () => {
  const { server, api, credentials, lost } = await setup({ access: 'synthetic-old', refresh: 'synthetic-refresh' });
  server.enqueue({ status: 401 }, { body: pair }, { status: 401 });
  await api.request('/api/meta');
  expect(lost()).toBe(0);
  expect(credentials.tokens()!.refresh).toBe('synthetic-next');
  expect(server.requests).toHaveLength(3);
});

test.each<[string, Reply]>([
  ['429 invalid_grant', { status: 429, body: '{"error":"invalid_grant"}' }],
  ['500 invalid_token', { status: 500, body: '{"error":"invalid_token"}' }],
  ['503', { status: 503 }],
  ['400 server_error', { status: 400, body: '{"error":"server_error"}' }],
  ['an empty 200', { body: '{}' }],
  ['a dropped connection', 'drop'],
])('a retryable refresh failure (%s) keeps the tokens', async (_, failure) => {
  const original = { access: 'synthetic-old', refresh: 'synthetic-refresh' };
  const { server, api, credentials, lost } = await setup(original);
  server.enqueue({ status: 401 }, failure);
  await expect(api.request('/api/meta')).rejects.toBeInstanceOf(NetworkError);
  expect(credentials.tokens()).toEqual(original);
  expect(server.requests).toHaveLength(2);
  server.enqueue({ status: 401 }, { body: pair }, { body: '{}' });
  expect((await api.request('/api/meta')).status).toBe(200);
  expect(credentials.tokens()!.refresh).toBe('synthetic-next');
  expect(server.requests.filter((request) => request.tokenEndpoint).map((request) => json(request).refresh_token))
    .toEqual(['synthetic-refresh', 'synthetic-refresh']);
  expect(lost()).toBe(0);
});

test('concurrent requests sharing credentials use exactly one refresh', async () => {
  const { server, api } = await setup({ access: 'synthetic-old', refresh: 'synthetic-refresh' });
  let release!: () => void;
  const bothUnauthorized = new Promise<void>((resolve) => { release = resolve; });
  let unauthorized = 0;
  server.dispatch(async (request) => {
    if (request.tokenEndpoint) return { body: pair };
    if (request.headers.Authorization === 'Bearer synthetic-old') {
      unauthorized += 1;
      if (unauthorized === 2) release();
      await bothUnauthorized;
      return { status: 401 };
    }
    return { body: '{}' };
  });
  const statuses = await Promise.all([api.request('/api/meta'), api.request('/api/meta')]);
  expect(statuses.map((response) => response.status)).toEqual([200, 200]);
  expect(server.requests).toHaveLength(5);
  expect(server.requests.filter((request) => request.tokenEndpoint)).toHaveLength(1);
});

test('logout revokes and wipes even when the server fails', async () => {
  const { server, api, credentials, lost } = await setup({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  await credentials.setPending({ verifier: 'v', state: 's', createdAt: 1 });
  server.enqueue({ status: 500 });
  await api.logout();
  expect(server.requests[0]).toMatchObject({ method: 'POST', url: 'https://fytorium.test/api/app/logout' });
  expect(server.requests[0].headers.Authorization).toBe('Bearer synthetic-access');
  expect(credentials.tokens()).toBeNull();
  expect(credentials.pending()).toBeNull();
  expect(lost()).toBe(1);
});

test('logout offline still wipes this phone', async () => {
  const { server, api, credentials, lost } = await setup({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  server.enqueue('drop');
  await api.logout();
  expect(credentials.tokens()).toBeNull();
  expect(lost()).toBe(1);
});

test('the version check reads the real meta shape', () => {
  const meta = '{"minAppVersion":null,"serverVersion":"synthetic-sha","today":"2030-06-02","timezone":"Europe/Oslo","role":"sitter"}';
  expect(requiresUpdate(200, meta, '1.0.0')).toBe(false);
  expect(requiresUpdate(200, meta.replace('null', '"1.0.1-rc.1"'), '1.0.0')).toBe(true);
  expect(requiresUpdate(200, meta.replace('null', '"1.0.0+build.1"'), '1.0.0')).toBe(false);
});

test('the version check handles a missing endpoint and semantic versions', () => {
  expect(requiresUpdate(404, '', '1.0.0')).toBe(false);
  expect(requiresUpdate(426, '', '1.0.0')).toBe(true);
  expect(requiresUpdate(200, '{"minAppVersion":"1.10.0"}', '1.9.0')).toBe(true);
  expect(requiresUpdate(200, '{"minAppVersion":"1.0.0"}', '1.0.0')).toBe(false);
  expect(requiresUpdate(200, '{"minAppVersion":"0.9.0"}', '1.0.0')).toBe(false);
});

test('paths outside the API are refused before any request', async () => {
  const { server, api } = await setup();
  for (const path of ['/auth/login', '/api/../x', '/api/x?y=1', '/api/x#y']) await expect(api.request(path)).rejects.toThrow('Invalid API path');
  expect(server.requests).toHaveLength(0);
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

test('a refresh that starts during the logout request cannot restore the session', async () => {
  const { server, api, credentials } = await setup({ access: 'synthetic-old', refresh: 'synthetic-refresh' });
  const logoutReply = deferred<Reply>();
  const refreshReply = deferred<Reply>();
  server.dispatch((request) => {
    if (request.tokenEndpoint) return refreshReply.promise;
    if (request.url.endsWith('/api/app/logout')) return logoutReply.promise;
    return { status: 401 };
  });
  const logout = api.logout();
  await new Promise((resolve) => setImmediate(resolve));
  const pending = api.request('/api/meta');
  await new Promise((resolve) => setImmediate(resolve));
  logoutReply.resolve({ status: 204 });
  await logout;
  refreshReply.resolve({ body: pair });
  expect((await pending).status).toBe(401);
  expect(credentials.tokens()).toBeNull();
  expect(jest.requireMock('expo-secure-store').__items.has('fytorium.tokens')).toBe(false);
});

test("a 401 that arrives after another member logged in is not replayed with that member's tokens", async () => {
  const { server, api, credentials } = await setup({ access: 'synthetic-a', refresh: 'synthetic-a-refresh' });
  const first = deferred<Reply>();
  const seen: (string | undefined)[] = [];
  server.dispatch((request) => {
    seen.push(request.headers.Authorization);
    return request.headers.Authorization === 'Bearer synthetic-a' ? first.promise : { body: '{}' };
  });
  const pending = api.request('/api/plants');
  await new Promise((resolve) => setImmediate(resolve));
  await credentials.clear();
  await credentials.setTokens({ access: 'synthetic-b', refresh: 'synthetic-b-refresh' });
  first.resolve({ status: 401 });
  expect((await pending).status).toBe(401);
  expect(seen).toEqual(['Bearer synthetic-a']);
  expect(credentials.tokens()!.access).toBe('synthetic-b');
});

test('any 426 asks the session to show the update stop with the minimum it names', async () => {
  const { server, api, updates } = await setup({ access: 'synthetic-old', refresh: 'synthetic-refresh' });
  server.enqueue({ status: 426, body: '{"error":"update_required","minAppVersion":"3.0.0"}' }, { status: 426, body: '' });
  await api.request('/api/notifications/subscriptions', { method: 'POST', body: {} });
  await api.request('/api/plants');
  expect(updates).toEqual(['3.0.0', null]);
});

test('the push gate closes before the wipe starts, and the wipe marker clears only after it finished', async () => {
  const { server, api, events, wipe } = await setup({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  server.enqueue({ status: 204 });
  await api.logout();
  expect(events).toEqual(['gate closed, wipe marker false', 'session lost, wipe marker false']);
  expect(wipe.get()).toBe(false);
});

test('a failed SecureStore delete keeps the wipe marker, and the next start refuses the stored tokens and retries', async () => {
  const store = jest.requireMock('expo-secure-store') as { __items: Map<string, string>; __failDeletes(value: boolean): void };
  const wipe = marker();
  const first = new Credentials(true, wipe);
  await first.setTokens({ access: 'synthetic-a', refresh: 'synthetic-a-refresh' });
  store.__failDeletes(true);
  await expect(first.clear()).rejects.toBeInstanceOf(WipeError);
  store.__failDeletes(false);
  expect(wipe.get()).toBe(true);
  expect(store.__items.has('fytorium.tokens')).toBe(true);
  const restarted = new Credentials(true, wipe);
  expect(restarted.tokens()).toBeNull();
  await restarted.setPending(null);
  expect(store.__items.has('fytorium.tokens')).toBe(false);
  expect(wipe.get()).toBe(false);
});

test('without a finished first run, stored credentials and a pending attempt are not used', async () => {
  const stored = new Credentials(true, marker());
  await stored.setTokens({ access: 'synthetic-a', refresh: 'synthetic-a-refresh' });
  await stored.setPending({ verifier: 'v', state: 's', createdAt: 1 });
  const blocked = new Credentials(false, marker());
  expect(blocked.tokens()).toBeNull();
  expect(blocked.pending()).toBeNull();
  await expect(blocked.setTokens({ access: 'x', refresh: 'y' })).resolves.toBe(false);
});

test('a browser that never opened cancels the attempt', async () => {
  const { api, credentials } = await setup();
  await api.loginUrl();
  await api.cancelLogin();
  expect(credentials.pending()).toBeNull();
});

const secureStore = () => jest.requireMock('expo-secure-store') as {
  __items: Map<string, string>; __failDeletes(value: boolean): void; __holdWrites(): () => void;
};

test('a token write that is still in progress when the session ends is not published', async () => {
  const credentials = new Credentials(true, marker());
  await credentials.setTokens({ access: 'synthetic-a', refresh: 'synthetic-a-refresh' });
  const release = secureStore().__holdWrites();
  const write = credentials.setTokens({ access: 'synthetic-a2', refresh: 'synthetic-a2-refresh' });
  await new Promise((resolve) => setImmediate(resolve));
  const wipe = credentials.clear();
  release();
  await expect(write).resolves.toBe(false);
  await wipe;
  expect(credentials.tokens()).toBeNull();
  expect(secureStore().__items.has('fytorium.tokens')).toBe(false);
});

test('while an earlier wipe is unfinished, a new login is refused instead of being wiped later', async () => {
  const wipe = marker();
  const credentials = new Credentials(true, wipe);
  await credentials.setTokens({ access: 'synthetic-a', refresh: 'synthetic-a-refresh' });
  secureStore().__failDeletes(true);
  await expect(credentials.clear()).rejects.toBeInstanceOf(WipeError);
  expect(credentials.pending()).toBeNull();
  await expect(credentials.setTokens({ access: 'synthetic-b', refresh: 'synthetic-b-refresh' })).rejects.toBeInstanceOf(WipeError);
  await expect(credentials.setPending({ verifier: 'v', state: 's', createdAt: 1 })).rejects.toBeInstanceOf(WipeError);
  expect(credentials.tokens()).toBeNull();
  secureStore().__failDeletes(false);
  await expect(credentials.setTokens({ access: 'synthetic-b', refresh: 'synthetic-b-refresh' })).resolves.toBe(true);
  expect(wipe.get()).toBe(false);
  expect(JSON.parse(secureStore().__items.get('fytorium.tokens')!).access).toBe('synthetic-b');
});

test('a logout whose local delete fails still ends the session and reports no error', async () => {
  const { server, api, credentials, lost, wipe } = await setup({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  server.enqueue({ status: 204 });
  secureStore().__failDeletes(true);
  await expect(api.logout()).resolves.toBeUndefined();
  secureStore().__failDeletes(false);
  expect(credentials.tokens()).toBeNull();
  expect(wipe.get()).toBe(true);
  expect(lost()).toBe(1);
});

test('a 426 that arrives after logout does not bring back the update stop', async () => {
  const { server, api, updates } = await setup({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  const held = deferred<Reply>();
  server.dispatch((request) => (request.url.endsWith('/api/app/logout') ? { status: 204 } : held.promise));
  const push = api.request('/api/notifications/subscriptions', { method: 'POST', body: {} });
  await new Promise((resolve) => setImmediate(resolve));
  await api.logout();
  held.resolve({ status: 426, body: '{"minAppVersion":"3.0.0"}' });
  await push;
  expect(updates).toEqual([]);
});

test('a logout whose wipe marker cannot be saved still deletes the stored login, so a restart finds nothing', async () => {
  const { server, api, credentials, wipe } = await setup({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  server.enqueue('drop');
  wipe.break(true);
  await api.logout();
  wipe.break(false);
  expect(credentials.tokens()).toBeNull();
  expect(secureStore().__items.has('fytorium.tokens')).toBe(false);
  expect(new Credentials(true, marker()).tokens()).toBeNull();
});

test('when neither the marker nor the delete succeeds, logout says so and no new login is stored', async () => {
  const { server, api, credentials, wipe } = await setup({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  server.enqueue('drop');
  wipe.break(true);
  secureStore().__failDeletes(true);
  await expect(api.logout()).rejects.toBeInstanceOf(WipeError);
  expect(credentials.tokens()).toBeNull();
  await expect(credentials.setTokens({ access: 'synthetic-b', refresh: 'synthetic-b-refresh' })).rejects.toBeInstanceOf(WipeError);
  expect(credentials.pending()).toBeNull();
  secureStore().__failDeletes(false);
  wipe.break(false);
  await expect(credentials.setTokens({ access: 'synthetic-b', refresh: 'synthetic-b-refresh' })).resolves.toBe(true);
});

test('on Android a marker that only reached memory does not count as saved', async () => {
  const server = fakeServer();
  const wipe = androidMarker();
  const credentials = new Credentials(true, wipe);
  await credentials.setTokens({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  const api = createApi({
    baseUrl: 'https://fytorium.test', credentials, transport: server.transport, appVersion: '2.0.0', platform: 'android',
    language: () => 'en', deviceName: () => 'Synthetic phone', beforeWipe: () => undefined, onSessionLost: () => undefined, onUpdateRequired: () => undefined,
  });
  server.enqueue('drop');
  wipe.break(true);
  secureStore().__failDeletes(true);
  await expect(api.logout()).rejects.toBeInstanceOf(WipeError);
  secureStore().__failDeletes(false);
  wipe.break(false);
});

test('a wipe that fails inside the logout request is still reported', async () => {
  const { server, api, wipe } = await setup({ access: 'synthetic-access', refresh: 'synthetic-refresh' });
  server.dispatch((request) => (request.tokenEndpoint ? { status: 400, body: '{"error":"invalid_grant"}' } : { status: 401 }));
  wipe.break(true);
  secureStore().__failDeletes(true);
  await expect(api.logout()).rejects.toBeInstanceOf(WipeError);
  secureStore().__failDeletes(false);
  wipe.break(false);
});

test('a marker that cannot be cleared blocks new login state until it can', async () => {
  const wipe = marker();
  const credentials = new Credentials(true, wipe);
  await credentials.setTokens({ access: 'synthetic-a', refresh: 'synthetic-a-refresh' });
  await credentials.clear();
  wipe.set(true);
  const restarted = new Credentials(true, wipe);
  wipe.break(true);
  await expect(restarted.setPending({ verifier: 'v', state: 's', createdAt: 1 })).rejects.toBeInstanceOf(WipeError);
  await expect(restarted.setTokens({ access: 'synthetic-b', refresh: 'synthetic-b-refresh' })).rejects.toBeInstanceOf(WipeError);
  wipe.break(false);
  await restarted.setPending({ verifier: 'v', state: 's', createdAt: 1 });
  expect(wipe.get()).toBe(false);
  expect(restarted.pending()).not.toBeNull();
});

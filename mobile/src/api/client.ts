import type { RawResponse, RequestOptions } from '../../modules/fytorium-native';
import { challenge, codeFrom, createAttempt, errorFrom, REDIRECT_URI } from '../session/pkce';
import { WipeError, type Credentials, type Tokens } from '../session/credentials';

export class NetworkError extends Error {}

export type Transport = (options: RequestOptions) => Promise<RawResponse>;
export type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
export type ApiOptions = { method?: Method; body?: unknown; query?: Record<string, string>; binary?: boolean };

type Deps = {
  baseUrl: string;
  credentials: Credentials;
  transport: Transport;
  appVersion: string;
  platform: 'android' | 'ios';
  language: () => string;
  deviceName: () => string;
  // Runs synchronously before the wipe, so a crash mid-wipe can never leave the push gate open.
  beforeWipe: () => void;
  onSessionLost: () => Promise<void> | void;
  // Any 426 means this app version is too old; it carries the server's minimum when it has one.
  onUpdateRequired: (minimum: string | null) => void;
  now?: () => number;
};

const ok = (status: number) => status >= 200 && status < 300;

export function createApi(deps: Deps) {
  const { baseUrl, credentials, transport } = deps;
  const now = deps.now ?? Date.now;

  async function call(options: RequestOptions): Promise<RawResponse> {
    try {
      return await transport(options);
    } catch {
      throw new NetworkError("Can't reach Fytorium.");
    }
  }

  // Code and refresh exchanges. A refused code is "invalid"; only a refused refresh credential ends the session.
  async function exchange(body: Record<string, string>, refreshing: boolean): Promise<Tokens | null> {
    const response = await call({
      method: 'POST', url: baseUrl + '/api/app/token', tokenEndpoint: true, body: JSON.stringify(body),
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'Accept-Language': deps.language() },
    });
    if (!ok(response.status)) {
      if (!refreshing) return null;
      let error: unknown;
      try { error = JSON.parse(response.body)?.error; } catch { error = undefined; }
      if ((response.status === 400 || response.status === 401) && (error === 'invalid_grant' || error === 'invalid_token')) return null;
      throw new NetworkError('Refresh unavailable. Please retry.');
    }
    let json: any;
    try { json = JSON.parse(response.body); } catch { throw new NetworkError('Token response unavailable'); }
    if (json?.token_type !== 'Bearer' || typeof json.access_token !== 'string' || typeof json.refresh_token !== 'string'
      || !json.access_token.trim() || !json.refresh_token.trim()) throw new NetworkError('Invalid token response');
    return { access: json.access_token, refresh: json.refresh_token };
  }

  async function loseSession() {
    deps.beforeWipe();
    try {
      await credentials.clear();
    } catch (error) {
      // With the marker saved, a restart and any new login retry the delete; without it the phone must say so.
      if (!(error instanceof WipeError) || !credentials.wipeRetried()) throw error;
    } finally {
      await deps.onSessionLost();
    }
  }

  // Refresh tokens rotate once, so every caller sharing these credentials waits for one exchange.
  // A refresh belongs to the session it started in; its result is dropped once that session ended.
  function refreshAfter(original: Tokens, session: number): Promise<Tokens | null> {
    if (credentials.session !== session) return Promise.resolve(null);
    const current = credentials.tokens();
    if (!current) return Promise.resolve(null);
    if (current.refresh !== original.refresh) return Promise.resolve(current);
    if (!credentials.refreshing) {
      credentials.refreshing = (async () => {
        try {
          const next = await exchange({ grant_type: 'refresh_token', refresh_token: current.refresh }, true);
          if (credentials.session !== session) return null;
          if (!next) {
            await loseSession();
            return null;
          }
          return (await credentials.setTokens(next, session)) ? next : null;
        } finally {
          credentials.refreshing = null;
        }
      })();
    }
    return credentials.refreshing;
  }

  async function request(path: string, options: ApiOptions = {}): Promise<RawResponse> {
    const method = options.method ?? 'GET';
    if (!path.startsWith('/api/') || path.includes('..') || path.includes('?') || path.includes('#')) throw new Error('Invalid API path');
    const query = new URLSearchParams(options.query ?? {}).toString();
    const url = baseUrl + path + (query ? '?' + query : '');
    const send = (tokens: Tokens | null) => call({
      method, url, binary: options.binary,
      body: method === 'GET' ? undefined : options.body === undefined ? '' : JSON.stringify(options.body),
      headers: {
        'X-App-Version': deps.appVersion, Accept: 'application/json', 'Accept-Language': deps.language(),
        ...(method !== 'GET' && options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(tokens ? { Authorization: 'Bearer ' + tokens.access } : {}),
      },
    });
    const session = credentials.session;
    const original = credentials.tokens();
    const response = await checked(send(original), session);
    if (response.status !== 401 || !original) return response;
    const refreshed = await refreshAfter(original, session);
    // No unauthenticated replay, no second refresh and no replay under another session.
    if (!refreshed || credentials.session !== session) return { ...response, body: '' };
    return checked(send(refreshed), session);
  }

  // A 426 counts only while the session that sent the request is still the current one.
  async function checked(pending: Promise<RawResponse>, session: number): Promise<RawResponse> {
    const response = await pending;
    if (response.status === 426 && credentials.session === session) {
      let minimum: string | null = null;
      try {
        const parsed = JSON.parse(response.body)?.minAppVersion;
        if (typeof parsed === 'string' && parsed.trim()) minimum = parsed;
      } catch { /* the stop still shows, without a version */ }
      deps.onUpdateRequired(minimum);
    }
    return response;
  }

  async function loginUrl(): Promise<string> {
    const attempt = createAttempt(now());
    await credentials.setPending(attempt);
    const params = new URLSearchParams({
      app: deps.platform, redirect_uri: REDIRECT_URI, code_challenge: await challenge(attempt.verifier),
      code_challenge_method: 'S256', state: attempt.state,
    });
    return `${baseUrl}/auth/login?${params}`;
  }

  // Null when logged in; otherwise the server's error code, or "invalid" for a redirect or exchange it refused.
  async function completeLogin(redirect: string): Promise<string | null> {
    const attempt = credentials.pending();
    if (!attempt) return 'invalid';
    const error = errorFrom(attempt, redirect, now());
    if (error) {
      await credentials.setPending(null);
      return error;
    }
    const code = codeFrom(attempt, redirect, now());
    if (!code) return 'invalid';
    // Only a validated redirect consumes the attempt.
    await credentials.setPending(null);
    const tokens = await exchange({
      grant_type: 'authorization_code', code, code_verifier: attempt.verifier, device_name: deps.deviceName().slice(0, 100),
    }, false);
    if (!tokens) return 'invalid';
    return (await credentials.setTokens(tokens)) ? null : 'invalid';
  }

  // The browser never opened, so the attempt can never complete.
  const cancelLogin = () => credentials.setPending(null);

  // The session ends on this phone even when Fytorium can't be told.
  async function logout(): Promise<void> {
    await credentials.refreshing?.catch(() => null);
    const session = credentials.session;
    try {
      if (credentials.tokens()) await request('/api/app/logout', { method: 'POST' });
    } catch (error) {
      // Offline logout still wipes this phone; a wipe that failed inside the request must still be reported.
      if (error instanceof WipeError) throw error;
    } finally {
      // A refused refresh during the logout request may already have ended this session.
      if (credentials.session === session) await loseSession();
    }
  }

  return { request, loginUrl, completeLogin, cancelLogin, logout };
}

export type Api = ReturnType<typeof createApi>;

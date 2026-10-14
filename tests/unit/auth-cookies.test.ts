import type { Cookies } from '@sveltejs/kit';
import { beforeEach, expect, test, vi } from 'vitest';

const fixture = vi.hoisted(() => ({ query: vi.fn() }));
vi.mock('../../src/lib/server/config', () => ({
  config: () => ({ oidcRedirectUrl: new URL('https://fytorium.example/auth/callback') }),
}));
vi.mock('../../src/lib/server/db', () => ({
  db: () => ({ query: fixture.query }),
  withTransaction: vi.fn().mockResolvedValue('/'),
}));
vi.mock('../../src/lib/server/household', () => ({ INVITE_COOKIE: 'fytorium_invite' }));
vi.mock('openid-client', async importOriginal => ({
  ...await importOriginal<typeof import('openid-client')>(),
  discovery: vi.fn().mockResolvedValue({}),
  buildAuthorizationUrl: vi.fn().mockReturnValue(new URL('https://oidc.example/authorize')),
  authorizationCodeGrant: vi.fn().mockResolvedValue({ claims: () => ({ sub: 'synthetic-member' }) }),
}));

import { finishLogin, identity, logout, startLogin } from '../../src/lib/server/auth';
import { GET as invite } from '../../src/routes/invite/[token]/+server';

function fakeCookies() {
  const values = new Map<string, string>();
  const cookies: Cookies = {
    get: name => values.get(name),
    getAll: () => [...values].map(([name, value]) => ({ name, value })),
    set: vi.fn((name, value) => { values.set(name, value); }),
    delete: vi.fn(name => { values.delete(name); }),
    serialize: vi.fn(),
  };
  return cookies;
}

const deletionOptions = { secure: true, httpOnly: true, sameSite: 'lax', path: '/', maxAge: 0 };

beforeEach(() => {
  fixture.query.mockReset().mockResolvedValue({ rows: [] });
});

test('HTTPS callback deletes invite and login cookies with their creation attributes', async () => {
  const cookies = fakeCookies();
  const token = 'a'.repeat(43);
  expect(() => invite({ params: { token }, cookies } as Parameters<typeof invite>[0])).toThrow(expect.objectContaining({ status: 303, location: '/auth/login' }));
  expect(cookies.set).toHaveBeenCalledWith('fytorium_invite', token, { ...deletionOptions, maxAge: 600 });
  await startLogin(cookies, new URL('https://fytorium.example/auth/login'));
  expect(cookies.set).toHaveBeenCalledWith('fytorium_login', expect.any(String), { ...deletionOptions, maxAge: 600 });
  fixture.query.mockResolvedValueOnce({ rows: [{ expires_at: new Date(Date.now() + 600_000) }] });
  expect(await finishLogin(new URL('https://fytorium.example/auth/callback'), cookies)).toBe('/');
  expect(cookies.delete).toHaveBeenCalledTimes(2);
  for (const name of ['fytorium_invite', 'fytorium_login']) {
    expect(cookies.delete).toHaveBeenCalledWith(name, deletionOptions);
    const creation = vi.mocked(cookies.set).mock.calls.find(([cookieName]) => cookieName === name)![2];
    expect(deletionOptions).toEqual({ ...creation, maxAge: 0 });
  }
  expect(cookies.set).toHaveBeenCalledWith('fytorium_session', expect.any(String), { ...deletionOptions, maxAge: 7 * 24 * 60 * 60 });

  const sessionCreation = vi.mocked(cookies.set).mock.calls.find(([name]) => name === 'fytorium_session')![2];
  vi.mocked(cookies.delete).mockClear();
  expect(await identity(cookies)).toBeNull();
  expect(cookies.delete).toHaveBeenCalledExactlyOnceWith('fytorium_session', deletionOptions);
  expect(deletionOptions).toEqual({ ...sessionCreation, maxAge: 0 });
});

test('HTTPS logout deletes the session with the same attributes as creation', async () => {
  const cookies = fakeCookies();
  await startLogin(cookies, new URL('https://fytorium.example/auth/login'));
  fixture.query.mockResolvedValueOnce({ rows: [{ expires_at: new Date(Date.now() + 600_000) }] });
  await finishLogin(new URL('https://fytorium.example/auth/callback'), cookies);
  const creation = vi.mocked(cookies.set).mock.calls.find(([name]) => name === 'fytorium_session')![2];
  vi.mocked(cookies.delete).mockClear();
  await logout(cookies);
  expect(cookies.delete).toHaveBeenCalledExactlyOnceWith('fytorium_session', deletionOptions);
  expect(deletionOptions).toEqual({ ...creation, maxAge: 0 });
});

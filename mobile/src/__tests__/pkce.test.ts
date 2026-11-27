import { challenge, codeFrom, createAttempt, errorFrom, REDIRECT_URI, type LoginAttempt } from '../session/pkce';

test('PKCE matches RFC 7636', async () => {
  await expect(challenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).resolves.toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  const first = createAttempt();
  expect(first.verifier).toHaveLength(43);
  expect(first.state).toHaveLength(43);
  expect(createAttempt().state).not.toBe(first.state);
  expect(first.state).not.toBe(first.verifier);
});

test('state, redirect and expiry are validated', () => {
  const login: LoginAttempt = { verifier: 'synthetic-verifier', state: 'synthetic-state', createdAt: 100 };
  const valid = `${REDIRECT_URI}?code=synthetic-code&state=synthetic-state`;
  expect(codeFrom(login, valid, 101)).toBe('synthetic-code');
  for (const redirect of [
    valid.replace('synthetic-state', 'wrong'), valid + '&state=synthetic-state', valid + '&code=other', valid + '#fragment',
    valid + '&error=access_denied', valid.replace(':/', '://evil/'), valid.replace('oauth2redirect', 'other'),
    valid.replace('com.epaflix.fytorium', 'other'), `${REDIRECT_URI}?state=synthetic-state`, `${REDIRECT_URI}?code=x&state=%XX`,
  ]) expect([redirect, codeFrom(login, redirect, 101)]).toEqual([redirect, null]);
  expect(codeFrom(login, valid, 600_101)).toBeNull();
  expect(codeFrom(login, valid, 99)).toBeNull();
  // W5a-P8: the server's reason reaches the login screen only from a redirect that belongs to this attempt.
  expect(errorFrom(login, valid + '&error=not_member', 101)).toBe('not_member');
  expect(errorFrom(login, `${REDIRECT_URI}?error=not_member&state=wrong`, 101)).toBeNull();
  expect(errorFrom(login, valid, 101)).toBeNull();
});

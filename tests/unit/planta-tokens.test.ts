import { expect, test } from 'vitest';
import { plantaTokens } from '../../src/lib/server/planta/tokens';

test.each(['Bearer', 'bearer', 'BEARER'])('accepts top-level and nested Planta tokens with %s scheme', tokenType => {
  const tokens = { accessToken: 'synthetic-access', refreshToken: 'synthetic-refresh', tokenType };
  for (const response of [tokens, { data: tokens }]) {
    expect(plantaTokens(response)).toEqual({ ...tokens, tokenType: 'Bearer' });
  }
});

test.each([null, {}, { accessToken: '', refreshToken: 'synthetic', tokenType: 'Bearer' }, { accessToken: 'synthetic', refreshToken: 'synthetic', tokenType: 'Basic' }])('refuses invalid authorization', response => {
  expect(() => plantaTokens(response)).toThrow();
});

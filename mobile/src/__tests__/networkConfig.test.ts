const { debugNetwork } = require('../../modules/fytorium-native/plugin.js');

test('debug builds allow cleartext only to the exact stack hosts', () => {
  expect(debugNetwork).toMatch(/<base-config cleartextTrafficPermitted="false" \/>/);
  const domains = [...debugNetwork.matchAll(/<domain includeSubdomains="(\w+)">([^<]+)<\/domain>/g)];
  expect(domains.map((match) => match[2]).sort()).toEqual(['app', 'fake-push', 'oidc']);
  expect(domains.every((match) => match[1] === 'false')).toBe(true);
});

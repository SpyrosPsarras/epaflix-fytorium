import adapter from '@sveltejs/adapter-node';
// hooks.server.ts enforces origin after authentication, exempting only verified bearer requests.
export default { kit: { adapter: adapter(), csrf: { trustedOrigins: ['*'] } } };

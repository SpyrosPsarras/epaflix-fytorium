import { env } from '$env/dynamic/private';
import { building } from '$app/environment';

function required(name: string): string {
  const value = env[name];
  if (!value) throw new Error(`Missing configuration: ${name}`);
  return value;
}

export function config() {
  const testClockEnabled = env.FYTORIUM_TEST_CLOCK === 'true';
  if (testClockEnabled && env.NODE_ENV !== 'test') {
    throw new Error('FYTORIUM_TEST_CLOCK requires NODE_ENV=test');
  }
  return {
    testClockEnabled,
    gbifBaseUrl: new URL(env.GBIF_BASE_URL ?? 'https://api.gbif.org/v1/'),
    databaseUrl: required('DATABASE_URL'),
    origin: new URL(required('ORIGIN')),
    oidcIssuer: new URL(required('OIDC_ISSUER')),
    oidcClientId: required('OIDC_CLIENT_ID'),
    oidcClientSecret: required('OIDC_CLIENT_SECRET'),
    oidcRedirectUrl: new URL(required('OIDC_REDIRECT_URL')),
    allowInsecureOidc: env.OIDC_ALLOW_INSECURE_HTTP === 'true',
  };
}

if (!building) config();

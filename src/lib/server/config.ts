import { env } from '$env/dynamic/private';
import { building } from '$app/environment';
import webPush from 'web-push';

function required(name: string): string {
  const value = env[name];
  if (!value) throw new Error(`Missing configuration: ${name}`);
  return value;
}

function positiveInteger(name: string, fallback: number) {
  const value = env[name] === undefined ? fallback : Number(env[name]);
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(`Invalid configuration: ${name}`);
  return value;
}

export function config() {
  const testClockEnabled = env.FYTORIUM_TEST_CLOCK === 'true';
  if (testClockEnabled && env.NODE_ENV !== 'test') {
    throw new Error('FYTORIUM_TEST_CLOCK requires NODE_ENV=test');
  }
  const plantaTestImageOrigin = env.PLANTA_TEST_IMAGE_ORIGIN ?? '';
  if (plantaTestImageOrigin && (!testClockEnabled || env.NODE_ENV !== 'test')) throw new Error('PLANTA_TEST_IMAGE_ORIGIN requires the test clock and NODE_ENV=test');
  if (plantaTestImageOrigin && new URL(plantaTestImageOrigin).protocol !== 'https:') throw new Error('PLANTA_TEST_IMAGE_ORIGIN requires HTTPS');
  const householdTimezone = env.HOUSEHOLD_TIMEZONE ?? 'Europe/Oslo';
  try { new Intl.DateTimeFormat('en', { timeZone: householdTimezone }); }
  catch { throw new Error(`Invalid configuration: HOUSEHOLD_TIMEZONE '${householdTimezone}' is not a valid time zone`); }
  return {
    testClockEnabled,
    vapidPublicKey: env.VAPID_PUBLIC_KEY ?? '',
    vapidPrivateKey: env.VAPID_PRIVATE_KEY ?? '',
    vapidSubject: env.VAPID_SUBJECT ?? '',
    pushWorkerEnabled: env.PUSH_WORKER_ENABLED !== 'false',
    // The synthetic transport is unreachable in production, including misconfigured deployments.
    testPushUrl: testClockEnabled && env.FYTORIUM_TEST_PUSH_URL ? new URL(env.FYTORIUM_TEST_PUSH_URL) : null,
    minioEndpoint: new URL(required('MINIO_ENDPOINT')),
    minioBucket: required('MINIO_BUCKET'),
    minioAccessKey: required('MINIO_ACCESS_KEY'),
    minioSecretKey: required('MINIO_SECRET_KEY'),
    householdTimezone,
    openMeteoBaseUrl: new URL(env.OPEN_METEO_BASE_URL ?? 'https://api.open-meteo.com/'),
    openMeteoClimateBaseUrl: new URL(env.OPEN_METEO_CLIMATE_BASE_URL ?? 'https://climate-api.open-meteo.com/'),
    weatherWorkerEnabled: env.WEATHER_WORKER_ENABLED !== 'false',
    perenualBaseUrl: new URL(env.PERENUAL_BASE_URL ?? 'https://perenual.com/'),
    perenualApiKey: env.PERENUAL_API_KEY ?? '',
    perenualDailyCap: positiveInteger('PERENUAL_DAILY_CAP', 100),
    perenualPerMinute: positiveInteger('PERENUAL_PER_MINUTE', 10),
    perenualMaxAttempts: positiveInteger('PERENUAL_MAX_ATTEMPTS', 5),
    perenualWorkerEnabled: env.PERENUAL_WORKER_ENABLED !== 'false',
    plantaTestImageOrigin,
    plantaBaseUrl: new URL(env.PLANTA_BASE_URL ?? 'https://public.planta-api.com/v1'),
    openPlantbookBaseUrl: new URL(env.OPEN_PLANTBOOK_BASE_URL ?? 'https://open.plantbook.io/api/v1/'),
    openPlantbookApiKey: env.OPEN_PLANTBOOK_API_KEY ?? '',
    openPlantbookDailyCap: positiveInteger('OPEN_PLANTBOOK_DAILY_CAP', 100),
    openPlantbookPerMinute: positiveInteger('OPEN_PLANTBOOK_PER_MINUTE', 10),
    openPlantbookMaxAttempts: positiveInteger('OPEN_PLANTBOOK_MAX_ATTEMPTS', 5),
    gbifBaseUrl: new URL(env.GBIF_BASE_URL ?? 'https://api.gbif.org/v1/'),
    plantnetBaseUrl: new URL(env.PLANTNET_BASE_URL ?? 'https://my-api.plantnet.org/v2/'),
    plantnetApiKey: env.PLANTNET_API_KEY ?? '',
    wikidataBaseUrl: new URL(env.WIKIDATA_BASE_URL ?? 'https://www.wikidata.org/'),
    nortaxaBaseUrl: new URL(env.NORTAXA_BASE_URL ?? 'https://nortaxa.artsdatabanken.no/'),
    databaseUrl: required('DATABASE_URL'),
    origin: new URL(required('ORIGIN')),
    oidcIssuer: new URL(required('OIDC_ISSUER')),
    oidcClientId: required('OIDC_CLIENT_ID'),
    oidcClientSecret: required('OIDC_CLIENT_SECRET'),
    oidcRedirectUrl: new URL(required('OIDC_REDIRECT_URL')),
    allowInsecureOidc: env.OIDC_ALLOW_INSECURE_HTTP === 'true',
  };
}

if (!building) {
  const settings = config();
  if (settings.vapidPublicKey || settings.vapidPrivateKey || settings.vapidSubject) {
    try { webPush.getVapidHeaders('https://push.example', settings.vapidSubject, settings.vapidPublicKey, settings.vapidPrivateKey, 'aes128gcm'); }
    catch { throw new Error('Invalid VAPID configuration: set a valid subject and key pair'); }
  }
}

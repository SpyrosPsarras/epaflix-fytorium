import { afterAll, beforeAll, expect } from 'vitest';
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import { spawn, type ChildProcess } from 'node:child_process';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { createPhotoStorage } from './photo-storage';
import { identificationResponse } from '../identification-fixture';
import { close, listen, startOidcProvider, TEST_CLIENT_SECRET } from './oidc-provider';

type FakeResponse = { status: number; body: unknown; headers?: Record<string, string>; delay?: number };
export function createApiHarness(options: { testClock?: boolean; photos?: boolean; careEnv?: Record<string, string>; weather?: (url: URL) => FakeResponse; perenual?: (path: string) => FakeResponse; openPlantbook?: (url: URL, authorization: string | undefined) => FakeResponse } = {}) {
  let postgres: StartedPostgreSqlContainer;
  const photos = createPhotoStorage();
  let postgresStopped = false;
  let provider: Awaited<ReturnType<typeof startOidcProvider>>;
  let app: ChildProcess;
  let base: string;
  let appOutput = '';
  let appEnv: NodeJS.ProcessEnv;
  let gbifResponse: unknown = { usageKey: 910001, scientificName: 'Testoria lucida Inventor', canonicalName: 'Testoria lucida', rank: 'SPECIES', kingdom: 'Plantae', matchType: 'EXACT', confidence: 99 };
  let gbifStatus = 200;
  let gbifDelay = 0;
  const gbifSpecies = new Map<number, unknown>();
  const names = {
    gbif: { payload: { results: [], endOfRecords: true } as unknown, status: 200, delay: 0 },
    wikidata: { payload: { entities: {} } as unknown, status: 200, delay: 0 },
    nortaxa: { payload: [] as unknown, status: 200, delay: 0 },
  };
  const nameRequests = new Map<string, number>();
  function nameResponse(source: keyof typeof names, response: import('node:http').ServerResponse, key: string) {
    const id = source + ':' + key;
    nameRequests.set(id, (nameRequests.get(id) ?? 0) + 1);
    const { payload, status, delay } = names[source];
    setTimeout(() => {
      response.writeHead(status, { 'content-type': 'application/json' });
      response.end(JSON.stringify(payload));
    }, delay);
  }
  const gbif = createServer((request, response) => {
    const url = new URL(request.url!, 'http://fake');
    if (/^\/species\/\d+\/vernacularNames$/.test(url.pathname)) return nameResponse('gbif', response, url.pathname.split('/')[2]);
    if (url.pathname === '/wikidata/w/api.php') {
      if (url.searchParams.get('action') === 'query' && /^haswbstatement:P846=\d+$/.test(url.searchParams.get('srsearch') ?? '')) {
        const key = url.searchParams.get('srsearch')!.split('=').pop();
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ query: { search: [{ title: 'Q' + key }] } }));
      } else if (url.searchParams.get('action') === 'wbgetentities') nameResponse('wikidata', response, url.searchParams.get('ids')!.slice(1));
      else response.writeHead(400).end();
      return;
    }
    if (url.pathname === '/nortaxa/api/v1/TaxonName/Search' && url.searchParams.has('Search')) return nameResponse('nortaxa', response, url.searchParams.get('Search')!);
    if (/^\/species\/\d+$/.test(url.pathname)) {
      const payload = gbifSpecies.get(Number(url.pathname.split('/').pop()));
      response.writeHead(payload ? 200 : 404, { 'content-type': 'application/json' });
      response.end(JSON.stringify(payload ?? {}));
      return;
    }
    if (url.pathname !== '/species/match' || url.searchParams.get('kingdom') !== 'Plantae' || url.searchParams.get('verbose') !== 'true') {
      response.writeHead(400).end();
      return;
    }
    const payload = gbifResponse;
    const status = gbifStatus;
    setTimeout(() => {
      response.writeHead(status, { 'content-type': 'application/json' });
      response.end(JSON.stringify(payload));
    }, gbifDelay);
  });

  const plantnetKey = randomUUID();
  let plantnetResponse: unknown = identificationResponse;
  let plantnetStatus = 200;
  let plantnetDelay = 0;
  const identificationRequests: { bytes: Buffer; contentType: string; pathname: string; relatedImages: string | null }[] = [];
  const plantnet = createServer(async (request, response) => {
    const url = new URL(request.url!, 'http://fake');
    if (request.method !== 'POST' || url.pathname !== '/identify/all' || url.searchParams.get('api-key') !== plantnetKey) {
      response.writeHead(401).end();
      return;
    }
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(chunk);
    identificationRequests.push({ bytes: Buffer.concat(chunks), contentType: request.headers['content-type'] ?? '', pathname: url.pathname, relatedImages: url.searchParams.get('include-related-images') });
    const payload = plantnetResponse;
    const status = plantnetStatus;
    setTimeout(() => {
      response.writeHead(status, { 'content-type': 'application/json' });
      response.end(JSON.stringify(payload));
    }, plantnetDelay);
  });

  const weather = createServer((request, response) => {
    const result = options.weather?.(new URL(request.url!, 'http://fake')) ?? { status: 503, body: {} };
    setTimeout(() => { response.writeHead(result.status, { 'content-type': 'application/json' }); response.end(JSON.stringify(result.body)); }, result.delay ?? 0);
  });
  const perenual = createServer((request, response) => {
    const result = options.perenual?.(new URL(request.url!, 'http://fake').pathname) ?? { status: 200, body: { data: [] } };
    setTimeout(() => {
      response.writeHead(result.status, { 'content-type': 'application/json', ...result.headers });
      response.end(JSON.stringify(result.body));
    }, result.delay ?? 0);
  });

  const openPlantbook = createServer((request, response) => {
    const result = options.openPlantbook?.(new URL(request.url!, 'http://fake'), request.headers.authorization) ?? { status: 200, body: { results: [] } };
    setTimeout(() => {
      response.writeHead(result.status, { 'content-type': 'application/json', ...result.headers });
      response.end(JSON.stringify(result.body));
    }, result.delay ?? 0);
  });

  beforeAll(async () => {
    postgres = await new PostgreSqlContainer('postgres:17-alpine').start();
    if (options.photos) await photos.start();
    provider = await startOidcProvider();
    const gbifPort = await listen(gbif);
    const plantnetPort = await listen(plantnet);
    const perenualPort = await listen(perenual);
    const weatherPort = await listen(weather);
    const openPlantbookPort = await listen(openPlantbook);
    const portServer = createServer();
    const port = await listen(portServer);
    await close(portServer);
    base = `http://127.0.0.1:${port}`;
    // start.mjs runs migrations before starting the HTTP listener.
    appEnv = { ...process.env, OPEN_METEO_BASE_URL: `http://127.0.0.1:${weatherPort}/`, OPEN_METEO_CLIMATE_BASE_URL: `http://127.0.0.1:${weatherPort}/`, WEATHER_WORKER_ENABLED: 'false', OPEN_PLANTBOOK_BASE_URL: `http://127.0.0.1:${openPlantbookPort}/api/v1/`, OPEN_PLANTBOOK_API_KEY: options.openPlantbook ? 'synthetic-plantbook-token' : '', PERENUAL_BASE_URL: `http://127.0.0.1:${perenualPort}/`, PERENUAL_API_KEY: 'synthetic-perenual-key', PERENUAL_WORKER_ENABLED: 'false', ...options.careEnv, GBIF_BASE_URL: `http://127.0.0.1:${gbifPort}/`, WIKIDATA_BASE_URL: `http://127.0.0.1:${gbifPort}/wikidata/`, NORTAXA_BASE_URL: `http://127.0.0.1:${gbifPort}/nortaxa/`, PLANTNET_BASE_URL: `http://127.0.0.1:${plantnetPort}/`, PLANTNET_API_KEY: plantnetKey, NODE_ENV: options.testClock ? 'test' : 'production', FYTORIUM_TEST_CLOCK: options.testClock ? 'true' : 'false', HOST: '127.0.0.1', PORT: String(port), ORIGIN: base, DATABASE_URL: postgres.getConnectionUri() + '?sslmode=disable', OIDC_ISSUER: provider.issuer, OIDC_CLIENT_ID: 'test-client', OIDC_CLIENT_SECRET: TEST_CLIENT_SECRET, OIDC_REDIRECT_URL: base + '/auth/callback', OIDC_ALLOW_INSECURE_HTTP: 'true', ...photos.env, BODY_SIZE_LIMIT: '41M' };
    app = spawn('node', ['scripts/start.mjs'], { env: appEnv, stdio: ['ignore', 'pipe', 'pipe'] });
    app.stdout!.on('data', data => appOutput += data);
    app.stderr!.on('data', data => appOutput += data);
    for (let attempt = 0; attempt < 100; attempt++) {
      if (app.exitCode !== null) throw new Error(appOutput);
      try { if ((await fetch(base + '/healthz')).ok) return; } catch { /* Wait for migrations and HTTP listener. */ }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error('App did not start: ' + appOutput);
  }, options.photos ? 600000 : 120000);

  async function stopPostgres() {
    await postgres.stop();
    postgresStopped = true;
  }

  afterAll(async () => {
    if (app && app.exitCode === null) {
      const exited = new Promise<void>(resolve => app.once('exit', () => resolve()));
      app.kill('SIGTERM');
      await exited;
    }
    if (provider) await close(provider.server);
    weather.closeAllConnections();
    await close(weather);
    openPlantbook.closeAllConnections();
    await close(openPlantbook);
    perenual.closeAllConnections();
    await close(perenual);
    gbif.closeAllConnections();
    await close(gbif);
    plantnet.closeAllConnections();
    await close(plantnet);
    if (postgres && !postgresStopped) await stopPostgres();
    await photos.stop();
  });

  async function login(subject = 'synthetic-owner', claims: { name?: string; email?: string } = { name: 'Test member' }) {
    provider.setSubject(subject, claims);
    const start = await fetch(base + '/auth/login', { redirect: 'manual' });
    expect(start.status).toBe(303);
    const transactionCookie = start.headers.getSetCookie()[0].split(';')[0];
    const authorization = await fetch(start.headers.get('location')!, { redirect: 'manual' });
    const callbackUrl = authorization.headers.get('location')!;
    const callback = await fetch(callbackUrl, { redirect: 'manual', headers: { cookie: transactionCookie } });
    const cookie = callback.headers.getSetCookie().find(value => value.startsWith('fytorium_session='))?.split(';')[0] ?? '';
    function request(path: string, method = 'GET', body?: unknown, session = cookie) {
      return fetch(base + path, { method, redirect: 'manual', headers: { cookie: session, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    }
    return { callback, callbackUrl, transactionCookie, cookie, request };
  }

  return {
    get base() { return base; },
    get storage() { return photos.storage; },
    bucket: photos.bucket,
    get minioOrigin() { return photos.minioOrigin; },
    failThumbnailWrites: photos.failThumbnailWrites,
    failPhotoDeletes: photos.failDeletes,
    get postgres() { return postgres; },
    get appOutput() { return appOutput; },
    get identificationKeyLeaked() { return appOutput.includes(plantnetKey); },
    login,
    setGbif(payload: unknown, status = 200, delay = 0) { gbifResponse = payload; gbifStatus = status; gbifDelay = delay; },
    identificationRequests,
    setPlantnet(payload: unknown = identificationResponse, status = 200, delay = 0) { plantnetResponse = payload; plantnetStatus = status; plantnetDelay = delay; },
    setGbifSpecies(key: number, payload: unknown) { gbifSpecies.set(key, payload); },
    async restartApp(overrides: NodeJS.ProcessEnv = {}) {
      const exited = new Promise<void>(resolve => app.once('exit', () => resolve()));
      app.kill('SIGTERM');
      await exited;
      app = spawn('node', ['scripts/start.mjs'], { env: { ...appEnv, ...overrides }, stdio: ['ignore', 'pipe', 'pipe'] });
      app.stdout!.on('data', data => appOutput += data);
      app.stderr!.on('data', data => appOutput += data);
      for (let attempt = 0; attempt < 100; attempt++) {
        if (app.exitCode !== null) throw new Error('App restart failed');
        try { if ((await fetch(base + '/healthz')).ok) return; } catch { /* Wait for startup. */ }
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      throw new Error('App restart timed out');
    },
    setNames(source: keyof typeof names, payload: unknown, status = 200, delay = 0) { names[source] = { payload, status, delay }; },
    nameCalls(source: keyof typeof names, key: string | number) { return nameRequests.get(source + ':' + key) ?? 0; },
    stopPostgres,
  };
}

import { afterAll, beforeAll, expect } from 'vitest';
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import { spawn, type ChildProcess } from 'node:child_process';
import { createServer } from 'node:http';
import { close, listen, startOidcProvider, TEST_CLIENT_SECRET } from './oidc-provider';

export function createApiHarness() {
  let postgres: StartedPostgreSqlContainer;
  let postgresStopped = false;
  let provider: Awaited<ReturnType<typeof startOidcProvider>>;
  let app: ChildProcess;
  let base: string;
  let appOutput = '';

  beforeAll(async () => {
    postgres = await new PostgreSqlContainer('postgres:17-alpine').start();
    provider = await startOidcProvider();
    const portServer = createServer();
    const port = await listen(portServer);
    await close(portServer);
    base = `http://127.0.0.1:${port}`;
    // start.mjs runs migrations before starting the HTTP listener.
    app = spawn('node', ['scripts/start.mjs'], { env: { ...process.env, NODE_ENV: 'production', HOST: '127.0.0.1', PORT: String(port), ORIGIN: base, DATABASE_URL: postgres.getConnectionUri() + '?sslmode=disable', OIDC_ISSUER: provider.issuer, OIDC_CLIENT_ID: 'test-client', OIDC_CLIENT_SECRET: TEST_CLIENT_SECRET, OIDC_REDIRECT_URL: base + '/auth/callback', OIDC_ALLOW_INSECURE_HTTP: 'true' }, stdio: ['ignore', 'pipe', 'pipe'] });
    app.stdout!.on('data', data => appOutput += data);
    app.stderr!.on('data', data => appOutput += data);
    for (let attempt = 0; attempt < 100; attempt++) {
      if (app.exitCode !== null) throw new Error(appOutput);
      try { if ((await fetch(base + '/healthz')).ok) return; } catch { /* Wait for migrations and HTTP listener. */ }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error('App did not start: ' + appOutput);
  });

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
    if (postgres && !postgresStopped) await stopPostgres();
  });

  async function login(subject = 'synthetic-owner') {
    provider.setSubject(subject);
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
    get postgres() { return postgres; },
    get appOutput() { return appOutput; },
    login,
    stopPostgres,
  };
}

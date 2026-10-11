import { createServer } from 'node:http';
import { beforeAll, afterAll } from 'vitest';
import { listen, close } from './oidc-provider';

export function syntheticPlant(id: string, nickname = 'Test import lantern') {
  return {
    id, names: { custom: nickname, scientific: 'Testoria lucida', variety: 'Invented stripe' },
    site: { name: 'Test import alcove' }, environment: { pot: { type: 'potterracotta', size: 18, soil: 'cactussoil' } },
    actions: {
      watering: { completed: { date: '2026-06-01T12:00:00Z' } },
      fertilizing: { completed: { date: '2026-05-25T12:00:00Z' } },
      misting: { completed: { date: '2026-06-02T12:00:00Z' } },
      cleaning: { completed: { date: '2026-05-20T12:00:00Z' } },
      repotting: { completed: { date: '2025-06-01T12:00:00Z' } },
    }, image: { url: null as string | null }, ignoredPrivateField: 'Synthetic field must not be retained',
  };
}
export function createFakePlanta() {
  const env: Record<string, string> = {};
  let plants = [syntheticPlant('synthetic-planta-20-a'), syntheticPlant('synthetic-planta-20-b', 'Test import glow')];
  let mode = 'normal';
  let attempts = 0;
  const calls: { path: string; at: number }[] = [];
  const access = 'synthetic-import-access';
  const refresh = 'synthetic-import-refresh';
  const code = 'synthetic-import-code';
  const server = createServer(async (request, response) => {
    const url = new URL(request.url!, 'http://fake');
    calls.push({ path: url.pathname + url.search, at: Date.now() });
    const chunks: Buffer[] = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {};
    let status = 200;
    let result: unknown;
    if (url.pathname === '/v1/auth/authorize' && request.method === 'POST' && body.code === code) {
      result = { data: { accessToken: access, refreshToken: refresh, tokenType: 'Bearer' } };
    } else if (url.pathname === '/v1/auth/refreshToken' && request.method === 'POST' && body.refreshToken === refresh) {
      result = { data: { accessToken: access + '-new', refreshToken: refresh, tokenType: 'Bearer' } };
    } else if (url.pathname === '/v1/addedPlants' && request.method === 'GET' && [access, access + '-new'].some(token => request.headers.authorization === 'Bearer ' + token)) {
      attempts++;
      if (mode === 'unauthorized' || (mode === 'refresh' && attempts === 2)) status = 401;
      else if (mode === 'rate-limit' && attempts === 1 || mode === 'always-rate-limit') status = 429;
      const cursor = url.searchParams.get('cursor');
      result = { data: cursor ? plants.slice(1) : plants.slice(0, 1), pagination: { nextPage: !cursor && plants.length > 1 ? 'synthetic-next' : null } };
    } else { status = 401; result = { message: code + access + refresh }; }
    if (mode === 'timeout' && url.pathname === '/v1/addedPlants') await new Promise(resolve => setTimeout(resolve, 5500));
    response.writeHead(status, { 'content-type': 'application/json', ...(status === 429 ? { 'retry-after': '0.1' } : {}) });
    response.end(JSON.stringify(result));
  });
  beforeAll(async () => { env.PLANTA_BASE_URL = `http://127.0.0.1:${await listen(server)}/v1`; });
  afterAll(async () => { server.closeAllConnections(); await close(server); });
  return {
    env, code, access, refresh, calls,
    setPlants(value: typeof plants) { plants = value; },
    setMode(value: string) { mode = value; attempts = 0; calls.length = 0; },
  };
}

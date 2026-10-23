import { beforeAll, expect, test, vi } from 'vitest';
import { publicRoute } from '../../src/lib/server/policy';
vi.mock('../../src/lib/server/config', () => ({ config: () => ({}) }));
import { readdirSync, readFileSync } from 'node:fs';
import { createApiHarness } from './harness';
import { fixturePlant } from '../plant-fixture';
import ts from 'typescript';

const harness = createApiHarness({ testClock: true });
const ghost = '00000000-0000-4000-8000-000000000000';
const edit = (status: number, loggedOut = 401) => [status, status, 403, 403, loggedOut, loggedOut];
const ownerOnly = (status: number, loggedOut = 401) => [status, 403, 403, 403, loggedOut, loggedOut];
const self = (status: number, loggedOut = 401) => [status, status, status, status, loggedOut, loggedOut];
const shared = (status: number, loggedOut = 401) => [status, status, status, 404, loggedOut, loggedOut];
// Statuses for a valid household fixture with an empty write body. Denied roles must
// fail before validation or resource lookup. Owner/family exercise the actual handler.
const expectations: Record<string, Record<string, number[]>> = {
  '/__test/care-quota/[source]': { DELETE: ownerOnly(200) },
  '/api/app/logout': { POST: self(401) },
  '/api/feed': { GET: self(200) },
  '/api/feed/settings': { GET: edit(200), PUT: edit(422) },
  '/api/care-events/[id]/undo': { POST: [404, 404, 404, 404, 401, 401] },
  '/api/custom-tasks': { GET: edit(422), POST: edit(422) },
  '/api/custom-tasks/[id]': { GET: edit(404), PUT: edit(404), DELETE: edit(404) },
  '/api/household/invites': { GET: ownerOnly(200), POST: ownerOnly(422) },
  '/api/household/invites/[id]': { DELETE: ownerOnly(404) },
  '/api/household/members': { GET: ownerOnly(200) },
  '/api/household/members/[id]': { PUT: ownerOnly(404), DELETE: ownerOnly(404) },
  '/api/household/location': { GET: edit(200), PUT: ownerOnly(422) },
  '/api/identification': { GET: edit(200), POST: edit(400) },
  '/api/import/planta': { GET: ownerOnly(200), POST: ownerOnly(422) },
  '/api/import/planta/[id]': { PUT: ownerOnly(404) },
  '/api/import/planta/[id]/cover': { GET: ownerOnly(404) },
  '/api/import/planta/[id]/suggestion': { POST: ownerOnly(404) },
  '/api/import/planta/commit': { POST: ownerOnly(200) },
  '/api/journal': { GET: edit(200) },
  '/api/me': { GET: self(200) },
  '/api/meta': { GET: self(200) },
  '/api/notifications/key': { GET: self(200) },
  '/api/notifications/settings': { GET: self(200), PUT: self(422) },
  '/api/notifications/subscriptions': { GET: self(200), POST: self(422) },
  '/api/notifications/subscriptions/[id]': { DELETE: self(404), PATCH: self(422) },
  '/api/notifications/test': { POST: self(200) },
  '/api/photos/[id]': { DELETE: edit(404) },
  '/api/photos/[id]/[variant]': { GET: edit(404) },
  '/api/photos/[id]/cover': { PUT: edit(404) },
  '/api/choices': { GET: edit(200) },
  '/api/plants': { GET: self(200), POST: edit(422) },
  '/api/plants/count': { GET: self(200) },
  '/api/plants/[id]/weather': { GET: edit(200) },
  '/api/plants/[id]': { GET: shared(200), PUT: edit(404), DELETE: edit(404) },
  '/api/plants/[id]/care-settings': { GET: edit(404), PUT: edit(422) },
  '/api/plants/[id]/graveyard': { POST: edit(404) },
  '/api/plants/[id]/history': { GET: edit(404) },
  '/api/plants/[id]/photos': { GET: edit(404), POST: edit(404) },
  '/api/plants/[id]/repot': { POST: edit(404) },
  '/api/plants/[id]/restore': { POST: edit(404) },
  '/api/plants/[id]/species': { POST: edit(404) },
  '/api/plants/[id]/species-changes': { GET: edit(404) },
  '/api/plants/[id]/tasks': { GET: shared(200) },
  '/api/plants/[id]/tasks/[taskType]/[action]': { POST: shared(422) },
  '/api/plants/[id]/tasks/complete-all': { POST: shared(422) },
  '/api/sites': { GET: edit(200), POST: edit(422) },
  '/api/sites/[id]': { GET: edit(404), PUT: edit(422), DELETE: edit(404) },
  '/api/species': { GET: edit(200) },
  '/api/species/search': { GET: edit(422) },
  '/api/species/unmatched': { GET: edit(200) },
  '/api/species/[gbifKey]': { GET: edit(200) },
  '/api/species/[gbifKey]/photo': { GET: edit(404) },
  '/api/species/[gbifKey]/photo/credit': { GET: edit(200) },
  '/api/species/[gbifKey]/care': { GET: edit(200) },
  '/api/species/[gbifKey]/care/overrides/[field]': { PUT: edit(422), DELETE: edit(422) },
  '/api/species/[gbifKey]/care/refresh': { POST: edit(202) },
  '/api/species/[gbifKey]/care/retry-match': { POST: edit(409) },
  '/api/species/[gbifKey]/names': { POST: edit(422) },
  '/api/species/[gbifKey]/names/[id]': { DELETE: edit(404) },
  '/api/species/[gbifKey]/names/refresh': { POST: edit(202) },
  '/api/species/[gbifKey]/outdoor-months': { GET: edit(200) },
  '/api/task-batches/[id]/undo': { POST: [404, 404, 404, 404, 401, 401] },
  '/api/tasks': { GET: self(200) },
  '/api/tasks/[taskType]/complete-all': { POST: edit(422) },
  '/api/tasks/settings': { GET: edit(200), PUT: edit(422) },
  '/api/weather': { GET: edit(200) },
  '/api/weather/rain/water': { POST: edit(422) },
};
const pages: Record<string, number[]> = {
  '/': [200, 200, 200, 200, 303, 303], '/upcoming': [200, 200, 200, 200, 303, 303],
  '/members': ownerOnly(200, 303), '/import': ownerOnly(200, 303), '/settings': ownerOnly(200, 303),
  '/settings/notifications': self(200, 303), '/journal': edit(200, 303),
  '/plants': edit(200, 303), '/plants/new': edit(200, 303), '/plants/graveyard': edit(200, 303),
  '/plants/[id]': shared(200, 303), '/plants/[id]/settings': edit(200, 303),
  '/plants/[id]/updates': edit(200, 303), '/plants/[id]/repot': edit(200, 303), '/plants/[id]/species': edit(200, 303),
  '/sites': edit(200, 303), '/sites/new': edit(200, 303), '/sites/[id]': edit(404, 303), '/sites/[id]/edit': edit(404, 303),
  '/species/[gbifKey]': [200, 200, 403, 403, 303, 303], '/species/unmatched': edit(200, 303),
};
const root = 'src/routes';
const methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];
function exportedNames(source: string) {
  const names = new Set<string>();
  const file = ts.createSourceFile('route.ts', source, ts.ScriptTarget.Latest, true);
  for (const statement of file.statements) {
    if (ts.isExportDeclaration(statement) && statement.exportClause && ts.isNamedExports(statement.exportClause)) {
      for (const item of statement.exportClause.elements) names.add(item.name.text);
    } else if (ts.canHaveModifiers(statement) && ts.getModifiers(statement)?.some(modifier => modifier.kind === ts.SyntaxKind.ExportKeyword)) {
      if (ts.isFunctionDeclaration(statement) && statement.name) names.add(statement.name.text);
      if (ts.isVariableStatement(statement)) for (const declaration of statement.declarationList.declarations) {
        if (ts.isIdentifier(declaration.name)) names.add(declaration.name.text);
      }
    }
  }
  return names;
}
function handlerMethods(source: string) {
  const names = exportedNames(source);
  return methods.filter(method => names.has(method) || names.has('fallback'));
}
const files = readdirSync(root, { recursive: true }).filter((file): file is string => typeof file === 'string');
function pageFile(file: string, source: string) {
  return file.endsWith('+page.svelte')
    || /\+(?:page|layout)(?:\.server)?\.[tj]s$/.test(file) && exportedNames(source).has('load');
}
function serverFile(file: string) { return /\+server\.[tj]s$/.test(file); }
const pageRoutes = [...new Set(files.filter(file => /\+(?:page|layout)/.test(file) && pageFile(file, readFileSync(root + '/' + file, 'utf8')))
  .map(file => '/' + file.replace(/\/?\+[^/]+$/, '')).filter(route => !publicRoute(route)))];
const routes = files.filter(serverFile).flatMap(file => {
  const route = '/' + file.replace(/\/?\+server\.[tj]s$/, '');
  return publicRoute(route) ? [] : handlerMethods(readFileSync(root + '/' + file, 'utf8')).map(method => ({ route, method }));
});
const labels = ['owner', 'family', 'sitter with share', 'sitter without share', 'removed member', 'unauthenticated'];
let sessions: string[], accessTokens: string[], plant: string;
const transports = ['cookie', 'bearer'] as const;
function authHeaders(transport: typeof transports[number], index: number): Record<string, string> {
  return transport === 'cookie' ? { cookie: sessions[index] } : accessTokens[index] ? { authorization: 'Bearer ' + accessTokens[index] } : {};
}
beforeAll(async () => {
  const owner = await harness.login();
  const site = await (await owner.request('/api/sites', 'POST', { name: 'Coverage room', location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false })).json();
  const search = await (await owner.request('/api/species/search?q=Testoria%20lucida')).json();
  plant = (await (await owner.request('/api/plants', 'POST', { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 })).json()).id;
  sessions = [owner.cookie];
  accessTokens = [(await harness.appDevice()).access_token];
  for (const [index, role, shares] of [[1, 'family', []], [2, 'sitter', [plant]], [3, 'sitter', []], [4, 'family', []]] as const) {
    const invite = await (await owner.request('/api/household/invites', 'POST', { role, plant_ids: shares })).json();
    const member = await harness.login('coverage-' + index, {}, invite.link.split('/').at(-1));
    sessions.push(member.cookie);
    accessTokens.push((await harness.appDevice('coverage-' + index)).access_token);
    if (index === 4) {
      const id = (await (await member.request('/api/me')).json()).member.id;
      expect((await owner.request('/api/household/members/' + id, 'DELETE')).status).toBe(204);
    }
  }
  sessions.push('');
  accessTokens.push('');
});
test('route discovery handles fallback and named handler/load exports', () => {
  expect([...exportedNames('const x = () => {}; export { x as GET }; export const fallback = x;')].sort()).toEqual(['GET', 'fallback']);
  expect(serverFile('api/export/+server.js')).toBe(true);
  for (const file of ['+page.ts', '+layout.ts', '+page.server.ts', '+layout.server.ts']) {
    expect(pageFile(file, 'export const load = () => ({});')).toBe(true);
    expect(pageFile(file, 'export const prerender = true;')).toBe(false);
  }
  expect([...exportedNames('export { read as load } from "./reader";')]).toEqual(['load']);
  expect(handlerMethods('const x = () => {}; export { x as GET };')).toEqual(['GET']);
  expect(handlerMethods('export { handler as fallback } from "./handler";')).toEqual(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']);
});
test('the authorization table covers every page and server handler method derived from the filesystem, with no stale entries', () => {
  expect(pageRoutes.sort()).toEqual(Object.keys(pages).sort());
  const policySource = ts.createSourceFile('policy.ts', readFileSync('src/lib/server/policy.ts', 'utf8'), ts.ScriptTarget.Latest, true);
  const policyRoutes = new Set<string>();
  for (const statement of policySource.statements) if (ts.isVariableStatement(statement)) {
    for (const declaration of statement.declarationList.declarations) {
      if (ts.isIdentifier(declaration.name) && declaration.name.text === 'policies' && declaration.initializer && ts.isObjectLiteralExpression(declaration.initializer)) {
        for (const property of declaration.initializer.properties) if (property.name && ts.isStringLiteral(property.name)) policyRoutes.add(property.name.text);
      }
    }
  }
  for (const route of [...pageRoutes, ...routes.map(row => row.route)]) expect(policyRoutes.has(route), 'Missing explicit policy for ' + route).toBe(true);
  const actual = routes.map(row => row.method + ' ' + row.route).sort();
  const specified = Object.entries(expectations).flatMap(([route, methods]) => Object.keys(methods).map(method => method + ' ' + route)).sort();
  expect(actual).toEqual(specified);
});
for (const transport of transports) {
  for (const route of pageRoutes) {
    test.each(labels.map((label, index) => ({ label, index })))(`GET page ${route} via ${transport}: $label`, async ({ index }) => {
      const expected = pages[route]?.[index];
      expect(expected, 'Missing page authorization expectation for ' + route).toBeDefined();
      const path = route.replace('[id]', route.startsWith('/plants/') ? plant : ghost).replace('[gbifKey]', '910001');
      const response = await fetch(harness.base + path, { redirect: 'manual', headers: authHeaders(transport, index) });
      expect(response.status).toBe(expected);
      if (index >= 4) expect(response.headers.get('location')).toBe('/auth/login');
    });
  }
  for (const { route, method } of routes) {
    test.each(labels.map((label, index) => ({ label, index })))(`${method} ${route} via ${transport}: $label`, async ({ index }) => {
      const expected = expectations[route]?.[method]?.[index];
      expect(expected, 'Missing authorization expectation for ' + method + ' ' + route).toBeDefined();
      const usePlant = route === '/api/plants/[id]' && method === 'GET' || route.includes('/tasks') || route.endsWith('/weather');
      const path = route.replace('[id]', usePlant ? plant : ghost).replace('[gbifKey]', '910001').replace('[taskType]', 'water').replace('[action]', 'complete').replace('[variant]', 'thumb').replace('[field]', 'invalid_field').replace('[source]', 'perenual');
      // Logout requires bearer proof and revokes it. Use a fresh device so coverage stays independent.
      const headers = transport === 'bearer' && route === '/api/app/logout' && index < 4
        ? { authorization: 'Bearer ' + (await harness.appDevice(index === 0 ? 'synthetic-owner' : 'coverage-' + index)).access_token }
        : authHeaders(transport, index);
      const response = await fetch(harness.base + path, { method, redirect: 'manual', headers: { ...headers, 'content-type': 'application/json' }, body: method === 'GET' || method === 'HEAD' ? undefined : '{}' });
      expect(response.status).toBe(transport === 'bearer' && route === '/api/app/logout' && index < 4 ? 204 : expected);
    });
  }
}

test('bearer and cookie identities return the same sitter projections, and both deny unknown routes', async () => {
  for (let index = 0; index < labels.length; index++) {
    for (const path of ['/api/me', '/api/meta', '/api/plants/count', '/api/feed', '/api/tasks', '/api/plants/' + plant]) {
      const cookie = await fetch(harness.base + path, { headers: authHeaders('cookie', index) });
      const bearer = await fetch(harness.base + path, { headers: authHeaders('bearer', index) });
      expect(bearer.status).toBe(cookie.status);
      if (cookie.ok) expect(await bearer.json()).toEqual(await cookie.json());
    }
    for (const transport of transports) {
      expect((await fetch(harness.base + '/api/not-a-route', { headers: authHeaders(transport, index) })).status).toBe(404);
    }
  }
});

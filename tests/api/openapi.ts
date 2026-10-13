import { readFileSync } from 'node:fs';
import { parse } from 'yaml';
import Ajv2020, { type ValidateFunction } from 'ajv/dist/2020.js';

type Schema = Record<string, unknown>;
type ApiResponse = { content?: Record<string, { schema: Schema }> };
type Operation = { responses: Record<string, ApiResponse> };
export const spec = parse(readFileSync('api/openapi.yaml', 'utf8')) as {
  openapi: string; paths: Record<string, Record<string, Operation>>; components: { schemas: Record<string, Schema>; responses: Record<string, ApiResponse> };
};
const ajv = new Ajv2020({ strict: false, allErrors: true });
ajv.addSchema({ $id: 'fytorium', components: spec.components });
const validators = new Map<Schema, ValidateFunction>();
const routes = Object.entries(spec.paths).sort(([a], [b]) => (a.match(/{/g)?.length ?? 0) - (b.match(/{/g)?.length ?? 0))
  .map(([path, operations]) => ({ path, operations, pattern: new RegExp('^' + path.replace(/{[^}]+}/g, '[^/]+') + '$') }));

export async function assertApiResponse(method: string, url: string, response: Response) {
  const pathname = new URL(url).pathname;
  if (!pathname.startsWith('/api/')) return;
  const route = routes.find(route => route.pattern.test(pathname));
  if (!route) {
    if (response.status === 404 || response.status === 426) return;
    throw new Error('Missing OpenAPI route: ' + pathname);
  }
  let operation = route.operations[method.toLowerCase()];
  // The origin guard can reject even an unsupported method before the router sees it.
  if (method === 'HEAD' || !operation && response.status === 405) return;
  if (!operation && response.status >= 400) operation = { responses: {
    default: spec.components.responses.ApiError, '426': spec.components.responses.UpdateRequired,
  } };
  if (!operation) throw new Error('Missing OpenAPI operation: ' + method + ' ' + route.path);
  const documented = operation.responses[String(response.status)] ?? (response.status >= 400 ? operation.responses.default : undefined);
  if (!documented) throw new Error('Undocumented response status: ' + method + ' ' + route.path + ' ' + response.status);
  const contentType = response.headers.get('content-type')?.split(';')[0];
  if (!documented.content) {
    if ((await response.clone().text()) !== '') throw new Error('Expected an empty response: ' + pathname);
    return;
  }
  const schema = contentType ? documented.content[contentType]?.schema : undefined;
  if (!schema) throw new Error('Undocumented response content type: ' + pathname + ' ' + contentType);
  if (contentType !== 'application/json') return;
  let validate = validators.get(schema);
  if (!validate) {
    // Resolve the contract's local component references without copying schemas into tests.
    const resolved = JSON.parse(JSON.stringify(schema).replaceAll('#/components/', 'fytorium#/components/'));
    validate = ajv.compile(resolved);
    validators.set(schema, validate);
  }
  if (!validate(await response.clone().json())) throw new Error('OpenAPI response mismatch: ' + method + ' ' + route.path + ' ' + response.status + ' ' + JSON.stringify(validate.errors));
}

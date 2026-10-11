import { collectDefaultMetrics, Counter, Histogram, Registry } from 'prom-client';

export const registry = new Registry();
collectDefaultMetrics({ register: registry });
export const requests = new Counter({ name: 'fytorium_http_requests_total', help: 'HTTP responses by route, method and status', labelNames: ['route', 'method', 'status'], registers: [registry] });
export const duration = new Histogram({ name: 'fytorium_http_request_duration_seconds', help: 'Time to resolve an HTTP request', labelNames: ['route', 'method', 'status'], buckets: [.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10], registers: [registry] });

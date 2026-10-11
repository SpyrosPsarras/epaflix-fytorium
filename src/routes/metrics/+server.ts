import { registry } from '$lib/server/metrics';
export async function GET() {
  return new Response(await registry.metrics(), { headers: { 'Content-Type': registry.contentType } });
}

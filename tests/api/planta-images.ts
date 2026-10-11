import { beforeAll, afterAll } from 'vitest';
import { GenericContainer, type StartedTestContainer } from 'testcontainers';
import sharp from 'sharp';

export function createImageHost(env: Record<string, string>) {
  let host: StartedTestContainer;
  let admin: string;
  let image: Buffer;
  let url: string;
  async function stub(status: number, path = '/cover.jpg', headers: Record<string, string> = {}, body?: string, delay = 0) {
    await fetch(admin + '/__admin/mappings', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ priority: 1, request: { method: 'GET', urlPath: path }, response: { status, fixedDelayMilliseconds: delay, headers: { 'Content-Type': 'image/jpeg', ...headers }, ...(body ? { body } : { base64Body: image.toString('base64') }) } }),
    });
  }
  beforeAll(async () => {
    image = await sharp({ create: { width: 600, height: 800, channels: 3, background: '#448844' } }).jpeg().withMetadata({ orientation: 6 }).withExifMerge({ IFD3: { GPSLatitudeRef: 'N', GPSLatitude: '1/1 2/1 3/1', GPSLongitudeRef: 'E', GPSLongitude: '4/1 5/1 6/1' } }).toBuffer();
    host = await new GenericContainer('wiremock/wiremock:3.13.2').withCommand(['--https-port', '8443']).withExposedPorts(8080, 8443).start();
    admin = `http://${host.getHost()}:${host.getMappedPort(8080)}`;
    url = `https://localhost:${host.getMappedPort(8443)}/cover.jpg`;
    env.PLANTA_TEST_IMAGE_ORIGIN = new URL(url).origin;
    await stub(200);
  }, 120000);
  afterAll(async () => { if (host) await host.stop(); });
  return {
    get url() { return url; },
    async calls() {
      const result = await (await fetch(admin + '/__admin/requests/count', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ method: 'GET', urlPath: '/cover.jpg' }) })).json();
      return result.count as number;
    },
    async setStatus(status: number) { await fetch(admin + '/__admin/mappings', { method: 'DELETE' }); await stub(status); },
    stub,
  };
}

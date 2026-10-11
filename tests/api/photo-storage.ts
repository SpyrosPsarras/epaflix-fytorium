import { createServer, request as proxyRequest } from 'node:http';
import { createHash } from 'node:crypto';
import { GenericContainer, Wait, type StartedTestContainer } from 'testcontainers';
import { Client } from 'minio';
import { close, listen } from './oidc-provider';

export function createPhotoStorage() {
  let minio: StartedTestContainer | undefined;
  let storage: Client;
  let minioOrigin: string;
  let failThumbnailWrites = false;
  let failDeletes = false;
  const bucket = 'synthetic-photos';
  const env = { MINIO_ENDPOINT: 'http://127.0.0.1:1', MINIO_BUCKET: bucket, MINIO_ACCESS_KEY: 'synthetic-minio', MINIO_SECRET_KEY: 'synthetic-minio-password' };
  const proxy = createServer((request, response) => {
    if (failDeletes && request.method === 'POST' && new URL(request.url!, 'http://proxy').searchParams.has('delete')) {
      response.writeHead(403, { 'content-type': 'application/xml' });
      response.end('<Error><Code>AccessDenied</Code><Message>Synthetic object delete refusal</Message></Error>');
      return;
    }
    if (failThumbnailWrites && request.method === 'PUT' && request.url?.endsWith('-thumb.jpg')) {
      response.writeHead(403, { 'content-type': 'application/xml' });
      response.end('<Error><Code>AccessDenied</Code><Message>Synthetic thumbnail write refusal</Message></Error>');
      return;
    }
    const upstream = proxyRequest(minioOrigin + request.url, { method: request.method, headers: request.headers }, incoming => {
      response.writeHead(incoming.statusCode!, incoming.headers);
      incoming.pipe(response);
    });
    upstream.on('error', () => response.writeHead(503).end());
    request.pipe(upstream);
  });
  return {
    env,
    bucket,
    get storage() { return storage; },
    get minioOrigin() { return minioOrigin; },
    failThumbnailWrites(value: boolean) { failThumbnailWrites = value; },
    failDeletes(value: boolean) { failDeletes = value; },
    async start() {
      const tag = createHash('sha256').update(process.cwd()).digest('hex').slice(0, 12);
      const image = await GenericContainer.fromDockerfile(process.cwd(), 'tests/stack/Minio.Dockerfile').withTarget('server').build('fytorium-minio-api-' + tag, { deleteOnExit: false });
      minio = await image.withEnvironment({ MINIO_ROOT_USER: env.MINIO_ACCESS_KEY, MINIO_ROOT_PASSWORD: env.MINIO_SECRET_KEY }).withCommand(['server', '/data']).withExposedPorts(9000).withWaitStrategy(Wait.forHttp('/minio/health/live', 9000)).start();
      minioOrigin = `http://${minio.getHost()}:${minio.getMappedPort(9000)}`;
      env.MINIO_ENDPOINT = `http://127.0.0.1:${await listen(proxy)}`;
      storage = new Client({ endPoint: minio.getHost(), port: minio.getMappedPort(9000), useSSL: false, accessKey: env.MINIO_ACCESS_KEY, secretKey: env.MINIO_SECRET_KEY });
      await storage.makeBucket(bucket);
    },
    async stop() {
      if (minio) {
        proxy.closeAllConnections();
        await close(proxy);
        await minio.stop();
      }
    },
  };
}

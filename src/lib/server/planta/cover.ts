import { request } from 'node:https';
import { lookup } from 'node:dns/promises';
import { BlockList, isIP } from 'node:net';
import { error } from '@sveltejs/kit';
import { config } from '../config';
import { maxPhotoBytes } from '../images';

const blocked = new BlockList();
for (const [address, prefix] of [['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.168.0.0', 16], ['192.0.2.0', 24], ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 4], ['240.0.0.0', 4]] as const) blocked.addSubnet(address, prefix, 'ipv4');
const publicV6 = new BlockList();
publicV6.addSubnet('2000::', 3, 'ipv6');
for (const [address, prefix] of [['2001::', 32], ['2001:db8::', 32], ['2002::', 16]] as const) blocked.addSubnet(address, prefix, 'ipv6');
function publicAddress(address: string, family: number) {
  return family === 4 ? !blocked.check(address, 'ipv4') : publicV6.check(address, 'ipv6') && !blocked.check(address, 'ipv6');
}

export async function downloadCover(value: string): Promise<Buffer> {
  let url: URL;
  try { url = new URL(value); }
  catch { error(422, 'Planta cover URL is invalid. Skip this plant or fetch again.'); }
  if (url.protocol !== 'https:' || url.username || url.password) error(422, 'Planta cover photos require an HTTPS URL without credentials.');
  const hostname = url.hostname.replace(/^\[|\]$/g, '');
  // Only this configured synthetic origin may use private addresses and WireMock's self-signed certificate.
  // config() refuses the switch outside NODE_ENV=test with the test clock enabled.
  const testHost = config().plantaTestImageOrigin === url.origin;
  const signal = AbortSignal.timeout(5000);
  let addresses: { address: string; family: number }[];
  try {
    addresses = await new Promise((resolve, reject) => {
      const timeout = () => reject(new Error());
      signal.addEventListener('abort', timeout, { once: true });
      const result = isIP(hostname) ? Promise.resolve([{ address: hostname, family: isIP(hostname) }]) : lookup(hostname, { all: true });
      result.then(resolve, reject).finally(() => signal.removeEventListener('abort', timeout));
    });
  } catch { error(503, 'Planta cover host did not respond. Retry the staged import.'); }
  if (!addresses.length || !testHost && addresses.some(address => !publicAddress(address.address, address.family))) error(422, 'Planta cover host uses a private or reserved address. Download refused.');
  try {
    return await new Promise<Buffer>((resolve, reject) => {
      // Pin the validated address, retaining the original Host and TLS server name. No second DNS lookup.
      const req = request(url, { hostname: addresses[0].address, servername: isIP(hostname) ? undefined : hostname, headers: { host: url.host }, rejectUnauthorized: !testHost, signal }, response => {
        // Never follow redirects to another host, even for synthetic images.
        if (response.statusCode !== 200 || Number(response.headers['content-length'] ?? 0) > maxPhotoBytes) { response.destroy(); reject(new Error()); return; }
        const chunks: Buffer[] = [];
        let size = 0;
        response.on('data', (chunk: Buffer) => {
          size += chunk.length;
          if (size > maxPhotoBytes) { response.destroy(); reject(new Error()); }
          else chunks.push(chunk);
        });
        response.on('end', () => resolve(Buffer.concat(chunks)));
        response.on('error', reject);
        response.on('aborted', () => reject(new Error()));
      });
      req.on('error', reject);
      req.end();
    });
  } catch { error(503, 'Planta cover download failed or exceeded 15 MB. Retry the staged import, or skip this plant.'); }
}

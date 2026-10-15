import { Agent, request } from 'node:https';
import { error } from '@sveltejs/kit';
import { config } from '../config';
import { publicAddress, pinnedLookup, publicUrlLimits, PublicUrlError } from './public-url';

export async function downloadImage(value: string, label: string): Promise<Buffer> {
  let url: URL;
  try { url = new URL(value); }
  catch { error(422, `${label} URL is invalid.`); }
  const testHost = config().plantaTestImageOrigin === url.origin;
  const signal = AbortSignal.timeout(publicUrlLimits.timeoutMs);
  let address;
  try { address = await publicAddress(url, { allowPrivate: testHost, signal }); }
  catch (cause) {
    if (cause instanceof PublicUrlError && cause.reason === 'invalid_url') error(422, `${label} photos require an HTTPS URL without credentials.`);
    if (cause instanceof PublicUrlError && cause.reason === 'non_public') error(422, `${label} host uses a private or reserved address. Download refused.`);
    error(503, `${label} host did not respond.`);
  }
  const agent = new Agent({ lookup: pinnedLookup(address), rejectUnauthorized: !testHost });
  try {
    return await new Promise<Buffer>((resolve, reject) => {
      const req = request(url, { agent, signal }, response => {
        if (response.statusCode !== 200 || Number(response.headers['content-length'] ?? 0) > publicUrlLimits.maxBytes) { response.destroy(); reject(new Error()); return; }
        const chunks: Buffer[] = [];
        let size = 0;
        response.on('data', (chunk: Buffer) => {
          size += chunk.length;
          if (size > publicUrlLimits.maxBytes) { response.destroy(); reject(new Error()); }
          else chunks.push(chunk);
        });
        response.on('end', () => resolve(Buffer.concat(chunks)));
        response.on('error', reject);
        response.on('aborted', () => reject(new Error()));
      });
      req.on('error', reject);
      req.end();
    });
  } catch { error(503, `${label} download failed or exceeded 15 MB.`); }
  finally { agent.destroy(); }
}

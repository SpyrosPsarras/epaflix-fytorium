import { lookup } from 'node:dns/promises';
import type { LookupFunction } from 'node:net';
import { isIP } from 'node:net';
import ipaddr from 'ipaddr.js';
import { maxPhotoBytes } from '../images';

export const publicUrlLimits = { dnsMs: 2000, timeoutMs: 5000, maxBytes: maxPhotoBytes } as const;
export class PublicUrlError extends Error {
  constructor(readonly reason: 'invalid_url' | 'non_public', message: string) { super(message); }
}

type Address = { address: string; family: number };
function globalUnicast(address: string) {
  const ip = ipaddr.process(address);
  return ip.range() === 'unicast' && (ip.kind() === 'ipv4' || ip.match(ipaddr.parse('2000::'), 3));
}

// The private-origin exception is used only by the cover host configured for synthetic tests.
export async function publicAddress(url: URL, options: { allowPrivate?: boolean; signal?: AbortSignal } = {}): Promise<Address> {
  if (url.protocol !== 'https:' || url.username || url.password) throw new PublicUrlError('invalid_url', 'Expected an HTTPS endpoint without credentials');
  const host = url.hostname.replace(/^\[|\]$/g, '');
  const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(publicUrlLimits.dnsMs)]) : AbortSignal.timeout(publicUrlLimits.dnsMs);
  const addresses = await new Promise<Address[]>((resolve, reject) => {
    const abort = () => reject(new Error('DNS timed out'));
    if (signal.aborted) { abort(); return; }
    signal.addEventListener('abort', abort, { once: true });
    const answer = isIP(host) ? Promise.resolve([{ address: host, family: isIP(host) }]) : lookup(host, { all: true });
    answer.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort));
  });
  if (!addresses.length || !options.allowPrivate && addresses.some(({ address }) => !globalUnicast(address))) throw new PublicUrlError('non_public', 'Endpoint must resolve only to public global unicast addresses');
  return addresses[0];
}

// Works with https.Agent and undici's connect.lookup. Neither performs a second DNS lookup.
export function pinnedLookup(address: Address): LookupFunction {
  return (_host, options, callback) => {
    if (options.all) callback(null, [address]);
    else callback(null, address.address, address.family);
  };
}

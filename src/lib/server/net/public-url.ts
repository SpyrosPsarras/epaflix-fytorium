import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';
import ipaddr from 'ipaddr.js';

// Pin the checked answer when connecting so a second DNS answer cannot bypass the guard.
// #20 has a similar user-URL guard on another branch; deduplicate during integration.
export async function publicAddress(url: URL) {
  if (url.protocol !== 'https:' || url.username || url.password) throw new Error('Expected an HTTPS endpoint');
  const host = url.hostname.replace(/^\[|\]$/g, '');
  const addresses = isIP(host) ? [{ address: host, family: isIP(host) }] : await Promise.race([
    lookup(host, { all: true }),
    new Promise<never>((_, reject) => { const timer = setTimeout(() => reject(new Error('DNS timed out')), 2000); timer.unref(); }),
  ]);
  if (!addresses.length || addresses.some(({ address }) => ipaddr.process(address).range() !== 'unicast')) throw new Error('Endpoint must resolve only to public addresses');
  return addresses[0];
}

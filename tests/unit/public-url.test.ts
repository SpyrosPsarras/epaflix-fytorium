import { expect, test, vi, afterEach } from 'vitest';
import { publicAddress, pinnedLookup } from '../../src/lib/server/net/public-url';
import type { LookupAddress } from 'node:dns';

const { dnsLookup } = vi.hoisted(() => ({ dnsLookup: vi.fn<() => Promise<LookupAddress[]>>() }));
vi.mock('node:dns/promises', () => ({ lookup: dnsLookup }));
afterEach(() => vi.resetAllMocks());

test.each(['127.0.0.1', '10.0.0.1', '100.64.0.1', '169.254.169.254', '172.16.0.1', '192.168.0.1', '192.0.2.1', '198.18.0.1', '198.51.100.1', '203.0.113.1', '224.0.0.1', '240.0.0.1', '[::1]', '[fc00::1]', '[fe80::1]', '[ff02::1]', '[2001:db8::1]', '[::ffff:127.0.0.1]', '[::ffff:192.168.0.1]', '[4000::1]'])('refuses non-global address %s', async host => {
  await expect(publicAddress(new URL('https://' + host + '/image'))).rejects.toThrow();
});

test.each(['http://8.8.8.8/image', 'https://synthetic:password@8.8.8.8/image'])('refuses unsafe URL %s', async url => {
  await expect(publicAddress(new URL(url))).rejects.toThrow();
});

test('refuses a DNS answer with even one private address', async () => {
  dnsLookup.mockResolvedValue([{ address: '8.8.8.8', family: 4 }, { address: '127.0.0.1', family: 4 }]);
  await expect(publicAddress(new URL('https://synthetic.example/image'))).rejects.toThrow();
});

test.each(['8.8.8.8', '[2606:4700:4700::1111]', '[::ffff:8.8.8.8]'])('accepts global unicast %s', async host => {
  const address = await publicAddress(new URL('https://' + host));
  expect(address.family).toBe(host.includes(':') ? 6 : 4);
});

test('pinned lookup returns only the checked address for both Node and undici lookup modes', async () => {
  const address = { address: '8.8.8.8', family: 4 };
  const lookupPinned = pinnedLookup(address);
  const single = vi.fn(), all = vi.fn();
  lookupPinned('synthetic.example', {}, single);
  lookupPinned('synthetic.example', { all: true }, all);
  expect(single).toHaveBeenCalledWith(null, '8.8.8.8', 4);
  expect(all).toHaveBeenCalledWith(null, [address]);
  expect(dnsLookup).not.toHaveBeenCalled();
});

import { expect, test } from '@playwright/test';
import { logIn } from './helpers/login';

test('set, advance and reset persist across requests and drive session expiry', async ({ page, request }) => {
  try {
    const set = await request.post('/__test/clock', { data: { action: 'set', at: '2030-01-01T00:00:00.000Z' } });
    expect(set.ok()).toBe(true);
    expect(await (await request.get('/__test/clock')).json()).toEqual({ now: '2030-01-01T00:00:00.000Z' });
    await logIn(page, 'synthetic-browser-owner');
    expect((await page.request.get('/api/me')).status()).toBe(200);
    const advance = await request.post('/__test/clock', { data: { action: 'advance', milliseconds: 7 * 24 * 60 * 60 * 1000 } });
    expect(advance.ok()).toBe(true);
    expect(await (await request.get('/__test/clock')).json()).toEqual({ now: '2030-01-08T00:00:00.000Z' });
    expect((await page.request.get('/api/me')).status()).toBe(401);
    expect((await request.post('/__test/clock', { data: { action: 'set', at: 'not-a-date' } })).status()).toBe(400);
    expect((await request.post('/__test/clock', { data: { action: 'advance', milliseconds: '1' } })).status()).toBe(400);
    expect(await (await request.get('/__test/clock')).json()).toEqual({ now: '2030-01-08T00:00:00.000Z' });
  } finally {
    expect((await request.post('/__test/clock', { data: { action: 'reset' } })).ok()).toBe(true);
  }
  const reset = await (await request.get('/__test/clock')).json();
  expect(Math.abs(Date.parse(reset.now) - Date.now())).toBeLessThan(5000);
});

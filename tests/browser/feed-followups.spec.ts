import { expect, test } from '@playwright/test';
import { logIn } from './helpers/login';
import { fixturePlant } from '../plant-fixture';

test('home refresh pauses during rain watering and undo, and late care lists every plant', async ({ page }) => {
  test.setTimeout(90000);
  const key = 100000000 + Math.floor(Math.random() * 800000000);
  const plants: { id: string; name: string }[] = [];
  let siteId: string | undefined;
  let release: (() => void) | undefined;
  await page.request.post('/__test/clock', { data: { action: 'set', at: '2030-05-31T12:00:00Z' } });
  await logIn(page, 'synthetic-browser-owner');
  const settings = await (await page.request.get('/api/feed/settings')).json();
  const location = await (await page.request.get('/api/household/location')).json();
  try {
    await page.request.put('/api/feed/settings', { data: { late_tasks: true, current_weather: true, weather_alerts: true, plant_warnings: true } });
    const site = await (await page.request.post('/api/sites', { data: { name: 'Test busy terrace ' + key, location: 'outdoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false } })).json();
    siteId = site.id;
    const search = await (await page.request.get('/api/species/search?q=' + encodeURIComponent('Testoria weather ' + key))).json();
    for (let i = 0; i < 4; i++) {
      const name = 'Test busy plant ' + key + '-' + i;
      const response = await page.request.post('/api/plants', { data: { ...fixturePlant, nickname: name, site_id: siteId, search_id: search.search_id, gbif_key: key } });
      expect(response.status()).toBe(201);
      plants.push({ id: (await response.json()).id, name });
    }
    await page.request.post('/__test/clock', { data: { action: 'set', at: '2030-06-01T12:00:00Z' } });
    await page.request.put('/api/household/location', { data: { location: { latitude: .5, longitude: .5 } } });
    await page.request.post('/__test/weather-worker');
    await page.clock.install();
    await page.goto('/');
    const rain = page.getByRole('article', { name: 'Rain report', exact: true });
    const water = rain.getByRole('button', { name: /^Mark \d+ outdoor plants watered$/ });
    await expect(water).toBeVisible();
    let reloads = 0;
    page.on('request', request => { if (new URL(request.url()).pathname.endsWith('/__data.json')) reloads++; });
    for (const action of ['water', 'undo']) {
      const path = action === 'water' ? '/api/weather/rain/water' : '/api/task-batches/*/undo';
      const gate = new Promise<void>(resolve => { release = resolve; });
      await page.route('**' + path, async route => { await gate; await route.continue(); });
      const button = action === 'water' ? water : rain.getByRole('button', { name: 'Undo rain watering', exact: true });
      await button.click();
      await expect(button).toBeDisabled();
      const before = reloads;
      await page.clock.runFor(31000);
      // Let the browser's network events arrive after the simulated timer fires.
      await page.waitForTimeout(300);
      expect.soft(reloads, 'timer must not invalidate data during ' + action).toBe(before);
      release!();
      await expect(rain).toContainText(action === 'water' ? /Marked \d+ outdoor plants watered\./ : 'Rain watering undone.');
      await page.unroute('**' + path);
    }
    const late = page.getByRole('article', { name: 'Late tasks', exact: true });
    for (const plant of plants) await expect.soft(late.getByRole('link', { name: plant.name, exact: true })).toHaveAttribute('href', '/plants/' + plant.id);
    // A forecast may reach the UI just before its rain event is reconciled.
    for (const message of ['No recent rain report for outdoor plants.', 'No active outdoor plants.', 'request_id already used for another action.']) {
      await page.route('**/api/weather/rain/water', route => route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message }) }));
      await water.click();
      await expect(rain).toContainText(message === 'No recent rain report for outdoor plants.' ? 'Try again in a moment.' : message);
      if (message !== 'No recent rain report for outdoor plants.') await expect(rain).not.toContainText('Try again in a moment.');
      await page.unroute('**/api/weather/rain/water');
    }
  } finally {
    release?.();
    await page.unrouteAll({ behavior: 'wait' });
    await page.request.put('/api/feed/settings', { data: settings });
    for (const plant of plants) await page.request.delete('/api/plants/' + plant.id, { data: { confirm_name: plant.name } });
    if (siteId) await page.request.delete('/api/sites/' + siteId);
    await page.request.put('/api/household/location', { data: location });
    await page.request.post('/__test/clock', { data: { action: 'reset' } });
  }
});

import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { logIn } from './helpers/login';
import { fixturePlant } from '../plant-fixture';

test('adds a plant by its Greek name and manages names on its species page on a phone', async ({ page }, testInfo) => {
  await logIn(page, 'synthetic-browser-owner');
  const request = page.request;
  const suffix = randomUUID();
  const site = await (await request.post('/api/sites', { data: { name: 'Test names room ' + suffix, location: 'indoor', light: 'part_sun', grow_light: false, humidity: 'normal', draft: false } })).json();
  const search = await (await request.get('/api/species/search?q=Testoria%20lucida')).json();
  expect((await request.post('/api/plants', { data: { ...fixturePlant, site_id: site.id, search_id: search.search_id, gbif_key: 910001 } })).status()).toBe(201);
  await expect.poll(async () => (await (await request.get('/api/species/910001')).json()).names).toEqual(expect.arrayContaining([
    expect.objectContaining({ name: 'Δοκιμόφυτο', language: 'el' }),
  ]));
  const screenshot = async (name: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(name), fullPage: true });
  };
  await page.goto('/plants/new');
  await page.getByLabel('Species name', { exact: true }).fill('Δοκιμόφυτο');
  await page.getByRole('button', { name: 'Search species', exact: true }).click();
  await expect(page.getByText('Matched Δοκιμόφυτο · Greek', { exact: true })).toBeVisible();
  await screenshot('names-search.png');
  await page.getByRole('button', { name: 'Choose Testoria lucida', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Confirm species', exact: true })).toHaveCount(0);
  const nickname = 'Test Greek sprout ' + suffix;
  await page.getByLabel('Nickname', { exact: true }).fill(nickname);
  await page.getByLabel('Site', { exact: true }).selectOption(site.id);
  await page.getByRole('button', { name: 'Save plant', exact: true }).click();
  await expect(page.getByRole('heading', { name: nickname, exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Species care and names', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Testoria lucida Inventor', exact: true })).toBeVisible();
  for (const lang of ['English', 'Norwegian', 'Greek', 'Latin']) await expect(page.getByRole('heading', { name: lang, exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Latin', exact: true })).toContainText('Testoria lucida');
  await expect(page.getByRole('region', { name: 'Latin', exact: true })).toContainText('Source: gbif');
  await expect(page.getByRole('region', { name: 'Norwegian', exact: true })).toContainText('Prøvespire');
  await expect(page.getByRole('region', { name: 'Greek', exact: true })).toContainText('Δοκιμόφυτο');
  await expect(page.getByRole('link', { name: 'Species and names from GBIF (CC BY 4.0)', exact: true })).toHaveAttribute('href', 'https://www.gbif.org/species/910001');
  await expect(page.getByText('Wikidata names are CC0.', { exact: true })).toBeVisible();
  await screenshot('species-names.png');
  const ownerName = 'Test owner name ' + suffix;
  await page.getByLabel('Owner name', { exact: true }).fill(ownerName);
  await page.getByLabel('Language', { exact: true }).selectOption('el');
  await page.getByRole('button', { name: 'Add name', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Greek', exact: true })).toContainText(ownerName);
  await screenshot('species-owner-name.png');
  await page.getByRole('button', { name: 'Refresh names', exact: true }).click();
  await expect.poll(async () => (await (await request.get('/api/species/910001')).json()).name_fetch_needed).toBe(false);
  await page.reload();
  await expect(page.getByRole('region', { name: 'Greek', exact: true })).toContainText(ownerName);
  await page.getByRole('button', { name: 'Remove ' + ownerName, exact: true }).click();
  await expect(page.getByText(ownerName, { exact: true })).toHaveCount(0);
});

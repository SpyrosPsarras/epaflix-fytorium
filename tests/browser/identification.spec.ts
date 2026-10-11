import { expect, test } from '@playwright/test';
import { randomBytes, randomUUID } from 'node:crypto';
import sharp from 'sharp';
import { logIn } from './helpers/login';
import { identificationQuotaExhausted } from '../../src/lib/identification';

test('shows quota exhaustion once after a 429', async ({ page }) => {
  await logIn(page, 'synthetic-browser-owner');
  await page.goto('/plants/new');
  await page.getByRole('button', { name: 'Identify from photos', exact: true }).click();
  const image = await sharp({ create: { width: 640, height: 480, channels: 3, background: '#608060' } }).jpeg().toBuffer();
  await page.getByLabel('Choose photos', { exact: true }).setInputFiles({ name: 'synthetic.jpg', mimeType: 'image/jpeg', buffer: image });
  await page.route('**/api/identification', route => route.fulfill({ status: 429, json: { message: identificationQuotaExhausted } }));
  await page.getByRole('button', { name: 'Identify plant', exact: true }).click();
  await expect(page.getByText(identificationQuotaExhausted, { exact: true })).toHaveCount(1);
  await expect(page.getByRole('status')).toHaveText(identificationQuotaExhausted);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('identifies two photos, shows ranked candidates and low quota, then saves the chosen species on a phone', async ({ page }, testInfo) => {
  const nickname = 'Test identified sprout ' + randomUUID();
  const siteName = 'Test identification site ' + randomUUID();
  const largeImage = await sharp(randomBytes(4000 * 3000 * 3), { raw: { width: 4000, height: 3000, channels: 3 } }).jpeg({ quality: 85 }).withMetadata({ orientation: 6 }).toBuffer();
  expect(largeImage.length).toBeGreaterThan(3 * 1024 * 1024);
  const images = [largeImage, await sharp({ create: { width: 640, height: 480, channels: 3, background: '#608060' } }).jpeg().toBuffer()];
  const screenshot = async (name: string) => {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath(name), fullPage: true });
  };
  await logIn(page, 'synthetic-browser-owner');
  expect((await page.request.post('/api/sites', { data: { name: siteName, location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false } })).status()).toBe(201);
  await page.goto('/plants/new');
  await page.getByRole('button', { name: 'Identify from photos', exact: true }).click();
  const camera = page.getByLabel('Take photo', { exact: true });
  await expect(camera).toHaveAttribute('accept', 'image/*');
  await expect(camera).toHaveAttribute('capture', 'environment');
  await expect(camera).not.toHaveAttribute('multiple');
  const input = page.getByLabel('Choose photos', { exact: true });
  await expect(input).toHaveAttribute('accept', 'image/*');
  await expect(input).not.toHaveAttribute('capture');
  await expect(input).toHaveAttribute('multiple', '');
  await input.setInputFiles(images.map((buffer, index) => ({ name: `synthetic-${index}.jpg`, mimeType: 'image/jpeg', buffer })));
  const shot = { name: 'synthetic-shot.jpg', mimeType: 'image/jpeg', buffer: images[1] };
  await camera.setInputFiles(shot);
  await expect(page.getByRole('button', { name: /^Remove photo / })).toHaveCount(3);
  await input.setInputFiles([shot, shot]);
  await expect(page.getByRole('button', { name: /^Remove photo / })).toHaveCount(5);
  await camera.setInputFiles(shot);
  await expect(page.getByRole('alert')).toHaveText('Choose at most five photos. Remove a photo before adding more.');
  await expect(page.getByRole('button', { name: /^Remove photo / })).toHaveCount(5);
  for (let index = 0; index < 3; index++) await page.getByRole('button', { name: /^Remove photo / }).last().click();
  await expect(page.getByRole('button', { name: /^Remove photo / })).toHaveCount(2);
  await screenshot('identification-upload.png');
  // Chromium's network request body omits multipart file bytes. Capture at fetch instead.
  await page.evaluate(() => {
    const originalFetch = window.fetch;
    window.fetch = async (input, init) => {
      if (input === '/api/identification' && init?.body instanceof FormData) {
        const image = init.body.getAll('images')[0];
        if (image instanceof Blob) {
          const reader = new FileReader();
          const captured = new Promise<string>(resolve => reader.onload = () => resolve(String(reader.result)));
          reader.readAsDataURL(image);
          document.documentElement.dataset.identificationUpload = await captured;
        }
      }
      return originalFetch(input, init);
    };
  });
  await page.getByRole('button', { name: 'Identify plant', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Choose a species', exact: true })).toBeVisible();
  const uploaded = await page.evaluate(() => document.documentElement.dataset.identificationUpload);
  if (!uploaded) throw new Error('Expected browser image upload');
  const resized = Buffer.from(uploaded.split(',')[1], 'base64');
  const metadata = await sharp(resized).metadata();
  expect(metadata).toMatchObject({ format: 'jpeg', width: 1536, height: 2048 });
  expect(resized.length).toBeLessThan(3 * 1024 * 1024);
  expect(metadata.exif).toBeUndefined();
  await expect(page.getByText('42 identifications remaining today.', { exact: true })).toBeVisible();
  const candidates = page.getByRole('article');
  await expect(candidates).toHaveCount(2);
  await expect(candidates.first()).toContainText('Testoria picta Inventor');
  await expect(candidates.first()).toContainText('97%');
  await expect(candidates.first()).toContainText('Test paintedleaf');
  await expect(candidates.last()).toContainText('Testoria lucida');
  await expect(candidates.last()).toContainText('21%');
  await expect(candidates.last()).toContainText('GBIF accepted name: Testoria lucens Inventor');
  await expect(candidates.last()).toContainText('fuzzy · 72% confidence');
  await screenshot('identification-candidates.png');
  await page.getByRole('button', { name: 'Choose Testoria lucens', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Confirm species', exact: true })).toBeVisible();
  await expect(page.getByLabel('Nickname', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Pl@ntNet suggested Testoria lucida Inventor, 21% identification score.', { exact: true })).toBeVisible();
  await expect(page.getByText('GBIF accepted name: Testoria lucens Inventor · fuzzy · 72% confidence', { exact: true })).toBeVisible();
  await screenshot('identification-gbif-confirm.png');
  await page.getByRole('button', { name: 'Back to candidates', exact: true }).click();
  await page.getByRole('button', { name: 'Choose Testoria picta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Confirm species', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Confirm Testoria picta', exact: true }).click();
  await page.getByLabel('Nickname', { exact: true }).fill(nickname);
  await page.getByLabel('Site', { exact: true }).selectOption({ label: siteName });
  await screenshot('identification-plant-form.png');
  await page.getByRole('button', { name: 'Save plant', exact: true }).click();
  await expect(page.getByRole('heading', { name: nickname, exact: true, level: 1 })).toBeVisible();
  await expect(page.getByText('Testoria picta Inventor', { exact: true })).toBeVisible();
  await expect(page.getByText('No photo updates yet.', { exact: true })).toBeVisible();
  await screenshot('identification-saved.png');
  await page.goto('/plants/new');
  await expect(page.getByText('42 identifications remaining today.', { exact: true })).toBeVisible();
});

import { expect, test } from '@playwright/test';
import { logIn } from './helpers/login';
import { addPlantForSite } from './helpers/plants';
import { randomUUID } from 'node:crypto';

test('enable a device, decrypt a test push, show it through the service worker and click back to Today', async ({ page, context, request, browser }, testInfo) => {
  const siteName = 'Notification room ' + randomUUID();
  const plantName = 'Notification fern ' + randomUUID();
  await context.grantPermissions(['notifications'], { origin: 'http://app:3000' });
  const synthetic = { ...await (await request.post('http://fake-push:8080/subscriptions')).json(), label: 'Notification device ' + randomUUID() };
  // Chromium's native subscribe contacts FCM. Replace only that external provider at the browser seam.
  await context.addInitScript(subscription => {
    PushManager.prototype.subscribe = async function(options) {
      if (!options?.userVisibleOnly || !options.applicationServerKey) throw new Error('Missing VAPID subscription options');
      return { ...subscription, toJSON: () => subscription } as PushSubscription;
    };
  }, synthetic);
  const cdp = await context.newCDPSession(page);
  const registrations: { registrationId: string; scopeURL: string }[] = [];
  cdp.on('ServiceWorker.workerRegistrationUpdated', event => registrations.push(...event.registrations));
  await cdp.send('ServiceWorker.enable');
  await logIn(page, 'synthetic-browser-owner');
  expect((await page.request.post('/api/sites', { data: { name: siteName, location: 'indoor', light: 'part_sun', humidity: 'normal', grow_light: false, draft: false } })).status()).toBe(201);
  await addPlantForSite(page.request, siteName, plantName);
  await page.goto('/settings/notifications');
  await expect(page.getByRole('heading', { name: 'Notifications', exact: true })).toBeVisible();
  const registering = page.waitForResponse(response => response.url().endsWith('/api/notifications/subscriptions') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Enable notifications on this device' }).click();
  const device = await (await registering).json();
  await expect(page.getByRole('status')).toHaveText('Notifications enabled on this device.');
  if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath('notifications-enabled.png'), fullPage: true });
  await page.getByRole('button', { name: 'Send test notification' }).click();
  await expect(page.getByRole('status')).toHaveText('Test notification sent.');
  const received = await (await request.get('http://fake-push:8080/received')).json();
  const delivery = received.find((row: { endpoint: string }) => row.endpoint === synthetic.endpoint);
  expect(delivery.payload).toEqual({ title: 'Fytorium', body: 'Notifications work on this device.', tag: 'fytorium-test', url: '/' });
  await expect.poll(() => registrations.find(registration => registration.scopeURL === 'http://app:3000/')?.registrationId).toBeTruthy();
  const registrationId = registrations.find(registration => registration.scopeURL === 'http://app:3000/')!.registrationId;
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await cdp.send('ServiceWorker.startWorker', { scopeURL: 'http://app:3000/' });
  const displayed: string[] = [];
  const closed: string[] = [];
  cdp.on('BackgroundService.backgroundServiceEventReceived', ({ backgroundServiceEvent: event }) => {
    if (event.origin !== 'http://app:3000/' || event.serviceWorkerRegistrationId !== registrationId) return;
    if (event.eventName === 'Notification displayed') displayed.push(event.instanceId);
    if (event.eventName === 'Notification closed') closed.push(event.instanceId);
  });
  await cdp.send('BackgroundService.startObserving', { service: 'notifications' });
  await cdp.send('BackgroundService.setRecording', { service: 'notifications', shouldRecord: true });
  async function deliverPush(data: string, tag: string) {
    const before = displayed.filter(value => value === tag).length;
    await cdp.send('ServiceWorker.deliverPushMessage', { origin: 'http://app:3000', registrationId, data });
    // An early getNotifications() can prune Chrome's database before the new notification is displayed.
    await expect.poll(() => displayed.filter(value => value === tag).length).toBe(before + 1);
  }
  await deliverPush(JSON.stringify(delivery.payload), 'fytorium-test');
  await expect.poll(() => page.evaluate(async () => {
    const worker = await navigator.serviceWorker.ready;
    return (await worker.getNotifications()).map(notification => ({ title: notification.title, body: notification.body, tag: notification.tag, url: notification.data.url, icon: notification.icon }));
  })).toEqual([{ title: 'Fytorium', body: 'Notifications work on this device.', tag: 'fytorium-test', url: '/', icon: 'http://app:3000/icon-192.png' }]);

  await deliverPush(JSON.stringify({ ...delivery.payload, tag: 'fytorium-daily' }), 'fytorium-daily');
  await expect.poll(() => page.evaluate(async () => (await (await navigator.serviceWorker.ready).getNotifications()).map(row => row.tag))).toContain('fytorium-daily');
  await deliverPush('not JSON', 'fytorium-fallback');
  await expect.poll(() => page.evaluate(async () => (await (await navigator.serviceWorker.ready).getNotifications()).map(row => row.tag).sort())).toEqual(['fytorium-daily', 'fytorium-fallback', 'fytorium-test']);
  await page.evaluate(async () => { for (const row of await (await navigator.serviceWorker.ready).getNotifications({ tag: 'fytorium-fallback' })) row.close(); });
  // close() is asynchronous. Finish removing the old fallback before reusing its tag.
  await expect.poll(() => closed).toContain('fytorium-fallback');
  await expect.poll(() => page.evaluate(async () => (await (await navigator.serviceWorker.ready).getNotifications()).map(row => row.tag).sort())).toEqual(['fytorium-daily', 'fytorium-test']);
  await deliverPush(JSON.stringify({ tag: 'fytorium-daily' }), 'fytorium-fallback');
  await expect.poll(() => page.evaluate(async () => (await (await navigator.serviceWorker.ready).getNotifications()).map(row => row.tag).sort())).toEqual(['fytorium-daily', 'fytorium-fallback', 'fytorium-test']);
  expect(await page.evaluate(async () => (await (await navigator.serviceWorker.ready).getNotifications()).find(row => row.tag === 'fytorium-daily')?.body)).toBe('Notifications work on this device.');

  // Dispatch Chrome's notificationclick in the actual worker, not in page code or a mocked handler.
  const browserCdp = await browser.newBrowserCDPSession();
  const targets = await browserCdp.send('Target.getTargets');
  const worker = targets.targetInfos.find(target => target.type === 'service_worker' && target.url === 'http://app:3000/service-worker.js');
  expect(worker).toBeDefined();
  const { sessionId } = await browserCdp.send('Target.attachToTarget', { targetId: worker!.targetId, flatten: false });
  await browserCdp.send('Target.sendMessageToTarget', { sessionId, message: JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: "self.registration.getNotifications().then(notifications => self.dispatchEvent(new NotificationEvent('notificationclick', { notification: notifications[0] })))", awaitPromise: true } }) });
  await expect(page).toHaveURL('http://app:3000/');
  await expect(page.getByRole('heading', { name: 'Today', exact: true })).toBeVisible();
  if (process.env.PLAYWRIGHT_KEEP_ARTIFACTS === '1') await page.screenshot({ path: testInfo.outputPath('notifications-today.png'), fullPage: true });
  await page.goto('/settings/notifications');
  await page.getByRole('button', { name: 'Remove ' + device.label, exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Device removed.');
  await browserCdp.detach();
  await cdp.detach();
});

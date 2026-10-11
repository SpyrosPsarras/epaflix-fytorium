import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { readNtfyTopic } from './helpers/ntfy';

test('ntfy helper reads a delivery from the real topic cache', async ({ request }) => {
  const topic = 'synthetic-' + randomUUID();
  const response = await request.post('http://ntfy/' + topic, { data: 'Synthetic care reminder' });
  expect(response.ok()).toBe(true);
  const published = await response.json();
  await expect.poll(async () => (await readNtfyTopic(request, topic)).map(message => message.id)).toContain(published.id);
  expect(await readNtfyTopic(request, topic)).toEqual(expect.arrayContaining([
    expect.objectContaining({ id: published.id, topic, message: 'Synthetic care reminder' }),
  ]));
});

import { expect, test } from 'vitest';
import { readJsonObject } from '../../src/lib/server/json';
const request = (body: string, headers: Record<string, string> = {}) => new Request('http://synthetic.test', { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body });
test('reads a JSON object at the byte limit', async () => {
  const body = JSON.stringify({ text: 'x'.repeat(65525) });
  expect(new TextEncoder().encode(body).length).toBe(65536);
  expect(await readJsonObject(request(body))).toEqual({ text: 'x'.repeat(65525) });
});
test('refuses an oversized body measured in bytes, not characters', async () => {
  await expect(readJsonObject(request(JSON.stringify({ text: 'é'.repeat(32768) })))).rejects.toMatchObject({ status: 413 });
});
test('stops reading an oversized stream without trusting content-length', async () => {
  let cancelled = false;
  let chunks = 0;
  const body = new ReadableStream({ pull(controller) { if (++chunks <= 10) controller.enqueue(new Uint8Array(40000)); else controller.close(); }, cancel() { cancelled = true; } });
  const streamed = new Request('http://synthetic.test', { method: 'POST', headers: { 'content-type': 'application/json', 'content-length': '2' }, body, duplex: 'half' } as RequestInit);
  await expect(readJsonObject(streamed)).rejects.toMatchObject({ status: 413 });
  expect(cancelled).toBe(true);
});
test('returns 400 when the client aborts while reading the body', async () => {
  let chunks = 0;
  const body = new ReadableStream({ pull(controller) {
    if (++chunks === 1) controller.enqueue(new TextEncoder().encode('{'));
    else controller.error(new Error('synthetic client abort'));
  } });
  const streamed = new Request('http://synthetic.test', { method: 'POST', headers: { 'content-type': 'application/json' }, body, duplex: 'half' } as RequestInit);
  await expect(readJsonObject(streamed)).rejects.toMatchObject({ status: 400 });
  expect(body.locked).toBe(false);
});
test('keeps malformed JSON, wrong content type and non-objects at 400', async () => {
  for (const body of ['{', 'null', '[]', '42']) await expect(readJsonObject(request(body))).rejects.toMatchObject({ status: 400 });
  await expect(readJsonObject(request('{}', { 'content-type': 'text/plain' }))).rejects.toMatchObject({ status: 400 });
});

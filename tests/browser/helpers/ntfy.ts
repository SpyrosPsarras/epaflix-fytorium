import type { APIRequestContext } from '@playwright/test';

export type NtfyMessage = { id: string; topic: string; message: string; event: 'message' };

export async function readNtfyTopic(request: APIRequestContext, topic: string): Promise<NtfyMessage[]> {
  const response = await request.get('http://ntfy/' + encodeURIComponent(topic) + '/json?poll=1&since=all');
  if (!response.ok()) throw new Error('ntfy poll failed: ' + response.status());
  return (await response.text()).split('\n').filter(Boolean).map(line => JSON.parse(line))
    .filter(message => message.event === 'message');
}

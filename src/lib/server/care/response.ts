export type RequestResult = { body: unknown } | { retryLater: Date; quota?: boolean } | 'not_available' | 'auth_failed';

export async function careResponse(response: Response, options: { now: () => Date; backoff: (until: Date) => Promise<void> }): Promise<RequestResult> {
  if (response.status === 429) {
    const retry = response.headers.get('retry-after');
    const milliseconds = retry && /^\d+$/.test(retry) ? Number(retry) * 1000 : retry ? Date.parse(retry) - options.now().getTime() : 60000;
    const until = new Date(options.now().getTime() + Math.max(60000, Number.isFinite(milliseconds) ? milliseconds : 60000));
    await options.backoff(until);
    return { retryLater: until };
  }
  if ([401, 403].includes(response.status)) return 'auth_failed';
  if (response.status === 404) return 'not_available';
  if (!response.ok) return { retryLater: new Date(options.now().getTime() + 60000) };
  return { body: await response.json() };
}

import { error } from '@sveltejs/kit';
import { config } from '../config';

type Tokens = { accessToken: string; refreshToken: string; tokenType: string };
// One instance belongs to one fetch run. Nothing survives into review or commit.
export class PlantaClient {
  private tokens: Tokens | undefined;
  private refreshed = false;
  private deadline = AbortSignal.timeout(120_000);
  private base = config().plantaBaseUrl.href.replace(/\/$/, '') + '/';

  private async request(path: string, body?: object, authenticated = false): Promise<any> {
    for (let attempt = 0; attempt < 3; attempt++) {
      let response: Response;
      try {
        response = await fetch(new URL(path, this.base), {
          method: body ? 'POST' : 'GET', redirect: 'error',
          signal: AbortSignal.any([this.deadline, AbortSignal.timeout(5000)]),
          headers: { 'content-type': 'application/json', ...(authenticated && this.tokens ? { authorization: `${this.tokens.tokenType} ${this.tokens.accessToken}` } : {}) },
          body: body ? JSON.stringify(body) : undefined,
        });
      } catch { error(503, 'Planta did not respond. Start again with a new app code.'); }
      if (response.status === 401) {
        await response.body?.cancel();
        if (!authenticated || this.refreshed) error(502, 'Planta refused authorization. Start again with a new app code.');
        this.refreshed = true;
        await this.refresh();
        return this.request(path, body, authenticated);
      }
      if (response.status === 429) {
        const retryAfter = Number(response.headers.get('retry-after'));
        await response.body?.cancel();
        if (attempt === 2 || this.deadline.aborted) error(503, 'Planta is rate-limiting this import. Try again later with a new app code.');
        const wait = Math.min(2000, Math.max(100 * 2 ** attempt, Number.isFinite(retryAfter) ? retryAfter * 1000 : 0));
        await new Promise(resolve => setTimeout(resolve, wait));
        continue;
      }
      if (!response.ok) { await response.body?.cancel(); error(502, 'Planta could not read the plants. Try again with a new app code.'); }
      try {
        // Bound JSON before parsing, even when content-length is absent.
        const reader = response.body!.getReader();
        const chunks: Uint8Array[] = [];
        let size = 0;
        try {
          while (true) {
            const chunk = await reader.read();
            if (chunk.done) break;
            size += chunk.value.length;
            if (size > 5 * 1024 * 1024) { await reader.cancel(); throw new Error(); }
            chunks.push(chunk.value);
          }
        } finally { reader.releaseLock(); }
        return JSON.parse(Buffer.concat(chunks).toString());
      } catch { error(502, 'Planta returned an invalid response.'); }
    }
  }
  private acceptTokens(result: any) {
    const tokens = result?.data;
    if (typeof tokens?.accessToken !== 'string' || !tokens.accessToken || typeof tokens.refreshToken !== 'string' || !tokens.refreshToken || tokens.tokenType !== 'Bearer') error(502, 'Planta returned invalid authorization.');
    this.tokens = tokens;
  }
  async authorize(code: string) { this.acceptTokens(await this.request('auth/authorize', { code })); }
  async refresh() {
    if (!this.tokens) error(502, 'Planta authorization is missing.');
    this.acceptTokens(await this.request('auth/refreshToken', { refreshToken: this.tokens.refreshToken }));
  }
  async addedPlants(): Promise<unknown[]> {
    const plants: unknown[] = [];
    const cursors = new Set<string>();
    let cursor: string | null = null;
    for (let page = 0; page < 100; page++) {
      const result = await this.request('addedPlants' + (cursor ? '?cursor=' + encodeURIComponent(cursor) : ''), undefined, true);
      if (!Array.isArray(result?.data)) error(502, 'Planta returned invalid plants.');
      plants.push(...result.data);
      if (plants.length > 10_000) error(502, 'Planta import is too large.');
      cursor = result.pagination?.nextPage ?? null;
      if (!cursor) return plants;
      if (typeof cursor !== 'string' || cursors.has(cursor)) error(502, 'Planta returned invalid pagination.');
      cursors.add(cursor);
    }
    error(502, 'Planta import has too many pages.');
  }
  discard() { this.tokens = undefined; }
}

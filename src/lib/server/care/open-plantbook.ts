import { isHttpError } from '@sveltejs/kit';
import { mapOpenPlantbook } from '../../care/open-plantbook';
import { matchSpecies } from '../gbif';
import type { CareSource, CareResult } from './source';

export type PlantbookMatch = { status: 'matched'; pid: string } | { status: 'no_match' | 'ambiguous'; pid: null };
type RequestResult = { body: unknown } | { retryLater: Date; quota?: boolean } | 'not_available' | 'auth_failed';
const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};

export function openPlantbookSource(options: {
  baseUrl: URL; apiKey: string; now: () => Date;
  reserve: () => Promise<Date | null>; backoff: (until: Date) => Promise<void>;
  recordMatch: (key: number, match: PlantbookMatch) => Promise<void>;
}): CareSource {
  async function request(path: string, query: Record<string, string> = {}): Promise<RequestResult> {
    const wait = await options.reserve();
    if (wait) return { retryLater: wait, quota: true };
    const base = options.baseUrl.href;
    const url = new URL(path, base.endsWith('/') ? base : base + '/');
    url.search = new URLSearchParams(query).toString();
    try {
      const response = await fetch(url, { headers: { Authorization: 'Token ' + options.apiKey }, signal: AbortSignal.timeout(8000) });
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
    } catch {
      // Never propagate fetch exceptions, which can contain credentials.
      return { retryLater: new Date(options.now().getTime() + 60000) };
    }
  }
  return {
    configured: Boolean(options.apiKey),
    async fetch(species): Promise<CareResult> {
      let pid = species.sourceKey;
      if (!pid) {
        if (species.canonical_name.trim().length < 3) {
          await options.recordMatch(species.gbif_key, { status: 'no_match', pid: null });
          return 'not_available';
        }
        const search = await request('plant/search', { alias: species.canonical_name, limit: '200' });
        if (typeof search === 'string' || 'retryLater' in search) return search;
        const data = object(search.body);
        if (!Array.isArray(data.results)) return { retryLater: new Date(options.now().getTime() + 60000) };
        const confirmed = new Set<string>();
        let uncertain = Boolean(data.next);
        for (const row of data.results.map(object)) {
          if (typeof row.pid !== 'string' || !row.pid.trim() || row.pid.length > 200 || typeof row.display_pid !== 'string' || !row.display_pid.trim()) { uncertain = true; continue; }
          try {
            const candidates = await matchSpecies(row.display_pid);
            const same = candidates.find(candidate => candidate.gbif_key === species.gbif_key);
            if (!same) continue;
            if (candidates.length === 1 && same.match_type === 'EXACT' && same.rank === 'SPECIES' && !same.requires_confirmation) confirmed.add(row.pid);
            else uncertain = true;
          } catch (cause) {
            if (isHttpError(cause) && cause.status === 422) continue;
            return { retryLater: new Date(options.now().getTime() + 60000) };
          }
        }
        if (confirmed.size !== 1 || uncertain) {
          await options.recordMatch(species.gbif_key, { status: confirmed.size > 1 || uncertain ? 'ambiguous' : 'no_match', pid: null });
          return 'not_available';
        }
        pid = [...confirmed][0];
        await options.recordMatch(species.gbif_key, { status: 'matched', pid });
      }
      const detail = await request('plant/detail/' + encodeURIComponent(pid) + '/', { include: 'care' });
      if (typeof detail === 'string') return detail;
      if ('retryLater' in detail) return { ...detail, key: pid };
      return { fields: mapOpenPlantbook(detail.body), fetchedAt: options.now(), key: pid };
    },
  };
}

import type { CareField } from '$lib/schedule/engine';
import { config } from '../config';
import { now } from '../clock';
import { perenualSource } from './perenual';
import { reserveRequest, backoff } from './quota';
import type { CareSource, SourceLimits } from './source';

export type SourceName = Exclude<CareField['source'], 'override' | 'rule' | 'unknown'>;
export type RegisteredSource = { name: SourceName; source: CareSource; limits: SourceLimits; priority: number };
// Higher priority wins per field. Add an adapter and entry here for another source.
export function careSources(): RegisteredSource[] {
  const settings = config();
  const limits = { daily: settings.perenualDailyCap, perMinute: settings.perenualPerMinute, maxAttempts: settings.perenualMaxAttempts };
  const sources: RegisteredSource[] = [{ name: 'perenual', priority: 100, limits, source: perenualSource({
    baseUrl: settings.perenualBaseUrl, apiKey: settings.perenualApiKey, now,
    reserve: () => reserveRequest('perenual', limits), backoff: until => backoff('perenual', until),
  }) }];
  return sources.sort((a, b) => b.priority - a.priority);
}

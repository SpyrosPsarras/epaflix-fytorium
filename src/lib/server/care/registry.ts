import type { CareField } from '$lib/schedule/engine';
import { config } from '../config';
import { now } from '../clock';
import { perenualSource } from './perenual';
import { openPlantbookSource } from './open-plantbook';
import { recordPlantbookMatch } from './matches';
import { reserveRequest, backoff } from './quota';
import type { CareSource, SourceLimits } from './source';

export type SourceName = Exclude<CareField['source'], 'override' | 'rule' | 'unknown'>;
export type RegisteredSource = { name: SourceName; source: CareSource; limits: SourceLimits; priority: number; matchesByName: boolean };
// Higher priority wins per field. Add an adapter and entry here for another source.
export function careSources(): RegisteredSource[] {
  const settings = config();
  const limits = { daily: settings.perenualDailyCap, perMinute: settings.perenualPerMinute, maxAttempts: settings.perenualMaxAttempts };
  const plantbookLimits = { daily: settings.openPlantbookDailyCap, perMinute: settings.openPlantbookPerMinute, maxAttempts: settings.openPlantbookMaxAttempts };
  const sources: RegisteredSource[] = [{ name: 'perenual', matchesByName: false, priority: 100, limits, source: perenualSource({
    baseUrl: settings.perenualBaseUrl, apiKey: settings.perenualApiKey, now,
    reserve: () => reserveRequest('perenual', limits), backoff: until => backoff('perenual', until),
  }) }, { name: 'open_plantbook', matchesByName: true, priority: 50, limits: plantbookLimits, source: openPlantbookSource({
    baseUrl: settings.openPlantbookBaseUrl, apiKey: settings.openPlantbookApiKey, now,
    reserve: () => reserveRequest('open_plantbook', plantbookLimits), backoff: until => backoff('open_plantbook', until),
    recordMatch: recordPlantbookMatch,
  }) }];
  return sources.sort((a, b) => b.priority - a.priority);
}

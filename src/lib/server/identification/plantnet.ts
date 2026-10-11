import { error, isHttpError } from '@sveltejs/kit';
import { config } from '../config';
import { identificationLimits, identificationQuotaExhausted } from '$lib/identification';
import type { Identifier, IdentificationCandidate } from './identifier';

type Result = {
  score: number;
  species: { scientificNameWithoutAuthor: string; scientificNameAuthorship?: string; commonNames?: string[] };
  gbif?: { id?: string | number };
};

export const plantnet: Identifier = {
  async identify(images, organs) {
    const settings = config();
    if (!settings.plantnetApiKey.trim()) error(503, 'Photo identification is not configured. Search by name or configure Pl@ntNet.');
    const base = settings.plantnetBaseUrl.href;
    const url = new URL('identify/all', base.endsWith('/') ? base : base + '/');
    url.search = new URLSearchParams({ 'api-key': settings.plantnetApiKey, 'include-related-images': 'false' }).toString();
    const form = new FormData();
    for (const [index, image] of images.entries()) {
      form.append('images', new Blob([new Uint8Array(image)], { type: 'image/jpeg' }), `image-${index}.jpg`);
      form.append('organs', organs?.[index] ?? 'auto');
    }
    try {
      const response = await fetch(url, { method: 'POST', body: form, signal: AbortSignal.timeout(identificationLimits.timeoutMs) });
      if (response.status === 429) error(429, identificationQuotaExhausted);
      if (response.status === 401 || response.status === 403) error(503, 'Photo identification is unavailable. Check the Pl@ntNet API configuration.');
      if (response.status >= 500) error(503, 'Photo identification is unavailable. Pl@ntNet did not respond. Try again.');
      if (!response.ok) error(422, 'Pl@ntNet could not identify these photos. Try clearer photos or search by name.');
      const payload = await response.json();
      if (!payload || !Array.isArray(payload.results)) throw new Error('Invalid response');
      const candidates: IdentificationCandidate[] = [];
      for (const item of payload.results.slice(0, 10) as Result[]) {
        if (!item || typeof item.score !== 'number' || !Number.isFinite(item.score) || item.score < 0 || item.score > 1 || typeof item.species?.scientificNameWithoutAuthor !== 'string' || !item.species.scientificNameWithoutAuthor.trim()) continue;
        const key = Number(item.gbif?.id);
        const authorship = typeof item.species.scientificNameAuthorship === 'string' ? item.species.scientificNameAuthorship.trim() : '';
        candidates.push({
          gbifKey: Number.isSafeInteger(key) && key > 0 ? key : undefined,
          scientificName: [item.species.scientificNameWithoutAuthor.trim(), authorship].filter(Boolean).join(' '),
          canonicalName: item.species.scientificNameWithoutAuthor.trim(),
          score: item.score,
          commonNames: Array.isArray(item.species.commonNames) ? item.species.commonNames.filter(name => typeof name === 'string').slice(0, 20) : [],
        });
      }
      const remaining = payload.remainingIdentificationRequests;
      return { candidates: candidates.sort((a, b) => b.score - a.score), remaining: Number.isInteger(remaining) && remaining >= 0 ? remaining : undefined };
    } catch (cause) {
      if (isHttpError(cause)) throw cause;
      // Never log fetch errors: their URLs can contain the API key.
      error(503, 'Photo identification is unavailable. Pl@ntNet did not respond within 20 seconds or returned an invalid response. Try again.');
    }
  },
};

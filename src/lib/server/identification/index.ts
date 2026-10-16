import { error, isHttpError } from '@sveltejs/kit';
import type { Candidate } from '$lib/plants';
import { identificationLimits, identificationQuotaExhausted } from '$lib/identification';
import { db } from '../db';
import { imageForm, sanitiseImage } from '../images';
import { matchSpecies, speciesRank } from '../gbif';
import { recordSpeciesSearch } from '../species';
import { plantnet } from './plantnet';

export async function storeQuota(remaining: number) {
  await db().query('INSERT INTO identification_quota (singleton, remaining) VALUES (true, $1) ON CONFLICT (singleton) DO UPDATE SET remaining = EXCLUDED.remaining', [remaining]);
}
export async function identificationQuota(): Promise<{ remaining?: number }> {
  const result = await db().query('SELECT remaining FROM identification_quota WHERE singleton');
  const remaining: number | undefined = result.rows[0]?.remaining;
  return remaining !== undefined && remaining <= 50 ? { remaining } : {};
}

export async function identifyPlant(request: Request, household: string) {
  const form = await imageForm(request, identificationLimits.totalBytes, 'Identification photos must total at most 40 MB.');
  const files = form.getAll('images');
  if (files.length < 1 || files.length > 5) error(422, 'Choose one to five images of the same plant.');
  if (files.reduce((bytes, file) => bytes + (file instanceof File ? file.size : 0), 0) > identificationLimits.totalBytes) error(413, 'Identification photos must total at most 40 MB.');
  const images: Buffer[] = [];
  for (const file of files) {
    if (!(file instanceof File) || !file.size) error(422, 'Choose a decodable still image.');
    if (file.size > identificationLimits.imageBytes) error(413, 'Each identification image must be at most 15 MB.');
    images.push(await sanitiseImage(Buffer.from(await file.arrayBuffer()), identificationLimits.serverLongEdge));
  }
  let identification;
  try { identification = await plantnet.identify(images); }
  catch (cause) {
    if (isHttpError(cause) && cause.status === 429) await storeQuota(0);
    throw cause;
  }
  if (identification.remaining !== undefined) await storeQuota(identification.remaining);
  const candidates = await identificationCandidates(identification);
  if (!candidates.length) {
    if (identification.remaining === 0) error(429, identificationQuotaExhausted);
    error(422, 'No plant species could be identified. Try clearer photos or search by name.');
  }
  return { ...recordSpeciesSearch(candidates, household), ...await identificationQuota() };
}

export async function identificationCandidates(identification: Pick<Awaited<ReturnType<typeof plantnet.identify>>, 'candidates'>): Promise<Candidate[]> {
  const candidates: Candidate[] = [];
  for (const item of identification.candidates) {
    let candidate: Candidate;
    if (item.gbifKey) {
      const stored = await db().query('SELECT rank FROM species WHERE gbif_key = $1', [item.gbifKey]);
      const rank = stored.rows[0]?.rank ?? await speciesRank(item.gbifKey) ?? 'UNKNOWN';
      candidate = { gbif_key: item.gbifKey, scientific_name: item.scientificName, canonical_name: item.canonicalName, rank, match_type: null, confidence: null, requires_confirmation: true };
    } else {
      try {
        const mapped = await matchSpecies(item.scientificName, { alternatives: false });
        if (!mapped.length) continue;
        candidate = mapped[0];
      } catch (cause) {
        if (isHttpError(cause) && [422, 503].includes(cause.status)) continue;
        throw cause;
      }
    }
    if (candidates.some(existing => existing.gbif_key === candidate.gbif_key)) continue;
    candidates.push({ ...candidate, source: 'identification', requires_confirmation: true, score: item.score, common_names: item.commonNames, identification_name: item.scientificName });
  }
  return candidates;
}

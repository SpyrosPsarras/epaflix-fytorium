import { error, isHttpError } from '@sveltejs/kit';
import type { Candidate } from '$lib/plants';
import { identificationLimits, identificationQuotaExhausted } from '$lib/identification';
import type { Identity } from '../auth';
import { db } from '../db';
import { imageForm, sanitiseImage } from '../images';
import { identificationTaxonomy, matchSpecies } from '../gbif';
import { recordSpeciesSearch, withCommonNames } from '../species';
import { plantnet } from './plantnet';

export async function storeQuota(remaining: number) {
  await db().query('INSERT INTO identification_quota (singleton, remaining) VALUES (true, $1) ON CONFLICT (singleton) DO UPDATE SET remaining = EXCLUDED.remaining', [remaining]);
}
export async function identificationQuota(): Promise<{ remaining?: number }> {
  const result = await db().query('SELECT remaining FROM identification_quota WHERE singleton');
  const remaining: number | undefined = result.rows[0]?.remaining;
  return remaining !== undefined && remaining <= 50 ? { remaining } : {};
}

export async function identifyPlant(request: Request, identity: Identity) {
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
  try { identification = await plantnet.identify(images, { language: identity.language }); }
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
  const withName = await withCommonNames(candidates, identity.language);
  const named = candidates.map(candidate => {
    const item = withName(candidate);
    return { ...item, common_name: item.common_name ?? item.common_names?.[0] ?? null };
  });
  return { ...recordSpeciesSearch(named, identity.household.id), ...await identificationQuota() };
}

export async function identificationCandidates(identification: Pick<Awaited<ReturnType<typeof plantnet.identify>>, 'candidates'>): Promise<Candidate[]> {
  let unresolved = false;
  // Resolved together, so slow GBIF lookups cost one timeout, not one per candidate.
  const resolved = await Promise.all(identification.candidates.map(async (item): Promise<Candidate | null> => {
    if (item.gbifKey) {
      const taxonomy = await identificationTaxonomy(item.gbifKey);
      if (taxonomy.accepted) return taxonomy.accepted;
      // A known synonym is only offered under its accepted species, never under its own key.
      if (taxonomy.unresolved) { unresolved = true; return null; }
      const stored = await db().query<{ rank: string }>('SELECT rank FROM species WHERE gbif_key = $1', [item.gbifKey]);
      const rank = stored.rows[0]?.rank ?? taxonomy.rank ?? 'UNKNOWN';
      return { gbif_key: item.gbifKey, scientific_name: item.scientificName, canonical_name: item.canonicalName, rank, match_type: null, confidence: null, requires_confirmation: true };
    }
    try { return (await matchSpecies(item.scientificName, { alternatives: false }))[0] ?? null; }
    catch (cause) {
      if (isHttpError(cause) && [422, 503].includes(cause.status)) return null;
      throw cause;
    }
  }));
  const candidates: Candidate[] = [];
  for (const [index, candidate] of resolved.entries()) {
    if (!candidate || candidates.some(existing => existing.gbif_key === candidate.gbif_key)) continue;
    const item = identification.candidates[index];
    candidates.push({ ...candidate, source: 'identification', requires_confirmation: true, score: item.score,
      common_names: item.commonNames, identification_name: item.scientificName, identification_canonical_name: item.canonicalName });
  }
  if (!candidates.length && unresolved) error(503, 'Identification is unavailable. GBIF did not return the accepted species. Try again.');
  return candidates;
}

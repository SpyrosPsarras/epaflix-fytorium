import { error, isHttpError } from '@sveltejs/kit';
import type { ImportSuggestion } from '$lib/import';
import { now } from '../clock';
import { db } from '../db';
import { matchSpecies } from '../gbif';
import { identificationCandidates, storeQuota } from '../identification';
import { plantnet } from '../identification/plantnet';
import { isUuid } from '../plants';
import { coverThumbnail } from './cover';
import type { StoredImportStaging } from './import';

const pending = new Map<string, Promise<ImportSuggestion>>();

export async function importSuggestion(id: string, household: string): Promise<ImportSuggestion> {
  if (!isUuid(id)) error(404, 'Import plant not found');
  const key = household + ':' + id;
  const existing = pending.get(key);
  if (existing) return existing;
  const loading = suggest(id, household);
  pending.set(key, loading);
  try { return await loading; }
  finally { pending.delete(key); }
}

async function suggest(id: string, household: string): Promise<ImportSuggestion> {
  const suggestion: ImportSuggestion = { suggested_gbif_key: null, candidates: [], genus: null };
  const claimed = await db().query<Pick<StoredImportStaging, 'mapped' | 'candidates'>>(`UPDATE import_staging SET identified_at = $1
    WHERE id = $2 AND household_id = $3 AND status = 'review' AND identified_at IS NULL RETURNING mapped, candidates`, [now(), id, household]);
  const row = claimed.rows[0];
  if (!row) {
    const stored = await db().query<Pick<StoredImportStaging, 'suggestion'>>('SELECT suggestion FROM import_staging WHERE id = $1 AND household_id = $2', [id, household]);
    if (!stored.rowCount) error(404, 'Import plant not found');
    return stored.rows[0].suggestion ?? suggestion;
  }
  const genera = row.candidates.map(item => item.canonical_name.split(' ')[0]);
  if (genera.length && genera.every(genus => genus === genera[0]) && /^[A-Z][a-z]+$/.test(genera[0])) {
    try {
      suggestion.genus = (await matchSpecies(genera[0], { alternatives: false, genus: true }))
        .find(item => item.rank === 'GENUS' && item.canonical_name === genera[0]) ?? null;
    } catch {}
  }
  let remaining: number | undefined;
  if (row.mapped.cover_url) {
    try {
      const image = await coverThumbnail(id, household);
      const identified = await plantnet.identify([image]);
      remaining = identified.remaining;
      const best = identified.candidates[0];
      if (suggestion.genus && best && (best.gbifKey === suggestion.genus.gbif_key || best.canonicalName === suggestion.genus.canonical_name)) {
        suggestion.suggested_gbif_key = suggestion.genus.gbif_key;
      } else {
        const [top] = await identificationCandidates({ candidates: identified.candidates.slice(0, 1) });
        if (top && !row.candidates.some(candidate => candidate.gbif_key === top.gbif_key)) suggestion.candidates = [top];
        suggestion.suggested_gbif_key = top?.gbif_key ?? null;
      }
    } catch (cause) {
      if (isHttpError(cause) && cause.status === 429) remaining = 0;
    }
  }
  if (remaining !== undefined) await storeQuota(remaining);
  await db().query("UPDATE import_staging SET suggestion = $1 WHERE id = $2 AND household_id = $3 AND status = 'review'", [JSON.stringify(suggestion), id, household]);
  return suggestion;
}

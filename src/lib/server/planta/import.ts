import { requirePermission } from '../policy';
import { error, isHttpError } from '@sveltejs/kit';
import type { Identity } from '../auth';
import { db, withTransaction } from '../db';
import { now, calendarDate } from '../clock';
import { chosenSpecies, recordSpeciesSearch, searchSpecies } from '../species';
import { isUuid } from '../plants';
import type { ImportedFields, ImportStaging } from '$lib/import';
import { PlantaClient } from './client';

export function requireOwner(identity: Identity | null): Identity {
  if (!identity) error(401, 'Login required');
  requirePermission(identity, 'owner');
  return identity;
}
const pots: Record<string, ImportedFields['pot_type']> = {
  potplastic: 'plastic', potoriginalplastic: 'plastic', potterracotta: 'terracotta', potporcelain: 'ceramic',
  potglass: 'glass', potmetallic: 'metal', potwood: 'wood', potfabric: 'fabric', ground: 'in_ground',
};
const soils: Record<string, ImportedFields['soil_type']> = {
  allpurposepottingmix: 'all_purpose', allpurposegardensoil: 'all_purpose', pottingsoilanddrainage: 'all_purpose',
  cactussoil: 'cactus', cactuspalmcitrussoil: 'cactus', orchidpottingmix: 'orchid', epiphyticsoil: 'aroid', tropicalsoil: 'aroid',
};
const actions = { watering: 'water', fertilizing: 'fertilize', misting: 'mist', cleaning: 'clean_leaves', repotting: 'repot' } as const;
function text(value: unknown): string | null { return typeof value === 'string' ? value.trim().slice(0, 200) || null : null; }
function mapPlant(raw: any): { id: string; mapped: ImportedFields } {
  if (typeof raw?.id !== 'string' || !raw.id || raw.id.length > 200) error(502, 'Planta returned an invalid plant ID.');
  const pot = raw.environment?.pot;
  const potType = text(pot?.type)?.toLowerCase() ?? '';
  const soilType = text(pot?.soil)?.toLowerCase() ?? '';
  const care: ImportedFields['care'] = {};
  for (const [action, task] of Object.entries(actions)) {
    const value = raw.actions?.[action]?.completed?.date;
    if (value === undefined || value === null || value === '') continue;
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2}))?$/.test(value)) error(422, 'Planta supplied an invalid last care date.');
    const date = new Date(value);
    const day = value.slice(0, 10);
    const validDay = day >= '0001-01-01' && Number.isFinite(Date.parse(day)) && new Date(day).toISOString().slice(0, 10) === day;
    const dateOnly = value.length === 10;
    if (!validDay || !Number.isFinite(date.getTime()) || (dateOnly ? day > calendarDate(now()) : date.getTime() > now().getTime())) error(422, 'Planta supplied an invalid or future last care date.');
    care[task] = dateOnly ? day : date.toISOString();
  }
  return { id: raw.id, mapped: {
    nickname: text(raw.names?.custom), cultivar: text(raw.names?.variety), scientific_name: text(raw.names?.scientific) ?? text(raw.names?.localizedName) ?? '',
    site_name: text(raw.site?.name) ?? 'Imported plants',
    pot_type: Object.hasOwn(pots, potType) ? pots[potType] : 'other', soil_type: Object.hasOwn(soils, soilType) ? soils[soilType] : 'other',
    pot_size_cm: Number.isInteger(pot?.size) && pot.size > 0 && pot.size <= 2147483647 ? pot.size : null,
    care, cover_url: typeof raw.image?.url === 'string' && raw.image.url.length <= 4096 ? raw.image.url : null,
  } };
}
export type StoredImportStaging = Omit<ImportStaging, 'mapped' | 'can_commit' | 'can_review' | 'cover_status'> & { mapped: ImportedFields };
export async function storedStaging(household: string): Promise<StoredImportStaging[]> {
  const result = await db().query('SELECT id, planta_plant_id, mapped, candidates, selected, status, plant_id, cover_error FROM import_staging WHERE household_id = $1 ORDER BY planta_plant_id', [household]);
  return result.rows;
}
export async function staging(household: string): Promise<ImportStaging[]> {
  const rows = await storedStaging(household);
  const canCommit = !rows.some(row => row.status === 'review') && rows.some(row => row.status === 'confirmed');
  const covers = await db().query('SELECT planta_plant_id FROM plants WHERE household_id = $1 AND planta_cover_imported', [household]);
  const importedCovers = new Set(covers.rows.map(row => row.planta_plant_id));
  return rows.map(row => {
    const { cover_url, ...mapped } = row.mapped;
    const coverStatus: ImportStaging['cover_status'] = importedCovers.has(row.planta_plant_id) ? 'imported'
      : row.cover_error ? 'failed' : !cover_url ? 'none' : row.status === 'skipped' ? 'skipped' : 'pending';
    return { ...row, mapped: { ...mapped, has_cover: Boolean(cover_url) },
      can_commit: canCommit, can_review: row.status !== 'committed', cover_status: coverStatus };
  });
}
export async function stageImport(code: string, household: string) {
  const api = new PlantaClient();
  let plants: ReturnType<typeof mapPlant>[];
  try {
    await api.authorize(code);
    plants = (await api.addedPlants()).map(mapPlant);
  } finally { api.discard(); }
  const mappedRows: { id: string; mapped: ImportedFields; candidates: ImportStaging['candidates']; selected: ImportStaging['selected'] }[] = [];
  for (const plant of plants) {
    let candidates: ImportStaging['candidates'] = [];
    let selected: ImportStaging['selected'] = null;
    if (plant.mapped.scientific_name) {
      try {
        const search = await searchSpecies(plant.mapped.scientific_name, household);
        candidates = search.candidates;
        const exact = candidates.find(candidate => !candidate.requires_confirmation);
        if (exact) selected = chosenSpecies(search.search_id, exact.gbif_key, household, false);
      }
      catch (cause) { if (!isHttpError(cause) || ![422, 503].includes(cause.status)) throw cause; }
    }
    mappedRows.push({ ...plant, candidates, selected });
  }
  await withTransaction(async (client) => {
    await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
    // Replace only after a complete fetch and match. A failed fetch keeps the prior review intact.
    await client.query('DELETE FROM import_staging WHERE household_id = $1', [household]);
    for (const plant of mappedRows) {
      await client.query(`INSERT INTO import_staging (household_id, planta_plant_id, mapped, candidates, selected, status)
        VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (household_id, planta_plant_id) DO UPDATE
        SET mapped = EXCLUDED.mapped, candidates = EXCLUDED.candidates, selected = EXCLUDED.selected, status = EXCLUDED.status`,
        [household, plant.id, JSON.stringify(plant.mapped), JSON.stringify(plant.candidates), plant.selected ? JSON.stringify(plant.selected) : null, plant.selected ? 'confirmed' : 'review']);
    }
  });
  return staging(household);
}

export async function reviewImport(id: string, household: string, input: Record<string, unknown>) {
  if (!isUuid(id)) error(404, 'Import plant not found');
  await withTransaction(async (client) => {
    await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
    const result = await client.query('SELECT * FROM import_staging WHERE id = $1 AND household_id = $2 FOR UPDATE', [id, household]);
    const row = result.rows[0] as StoredImportStaging | undefined;
    if (!row) error(404, 'Import plant not found');
    if (row.status === 'committed') error(409, 'This plant is already imported. Fetch again to update it.');
    let selected = null;
    let status = 'skipped';
    if (input.skip !== true) {
      selected = input.search_id !== undefined
        ? chosenSpecies(input.search_id, input.gbif_key, household, input.confirmed)
        : chosenSpecies(recordSpeciesSearch(row.candidates, household).search_id, input.gbif_key, household, input.confirmed);
      if (!selected) error(422, 'Choose a species from the candidates or search again.');
      if (selected.requires_confirmation && input.confirmed !== true) error(422, 'Confirm this species before importing.');
      status = 'confirmed';
    }
    await client.query('UPDATE import_staging SET selected = $1, status = $2 WHERE id = $3 AND household_id = $4', [selected ? JSON.stringify(selected) : null, status, id, household]);
  });
  return (await staging(household)).find(row => row.id === id)!;
}

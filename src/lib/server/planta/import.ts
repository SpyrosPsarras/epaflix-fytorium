import { requirePermission } from '../policy';
import { error, isHttpError } from '@sveltejs/kit';
import type { Identity } from '../auth';
import { db, withTransaction } from '../db';
import { now, calendarDate } from '../clock';
import { chosenSpecies, recordSpeciesSearch, searchSpecies, withCommonNames } from '../species';
import { squash } from '../common-name';
import type { Candidate } from '$lib/plants';
import type { CommonNameLanguage } from '$lib/names';
import { isUuid } from '../plants';
import type { ImportedFields, ImportStaging, ImportSuggestion } from '$lib/import';
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
const actions = { watering: 'water', fertilizing: 'fertilize', cleaning: 'clean_leaves', repotting: 'repot' } as const;
function text(value: unknown): string | null { return typeof value === 'string' ? value.trim().slice(0, 200) || null : null; }
export function importNickname(fields: ImportedFields, ...species: Pick<Candidate, 'scientific_name' | 'canonical_name'>[]): string | null {
  if (fields.custom_name) return fields.custom_name;
  const localized = fields.localized_name ?? fields.nickname;
  return localized && ![fields.scientific_name, ...species.flatMap(item => [item.scientific_name, item.canonical_name])]
    .some(name => name && squash(name) === squash(localized)) ? localized : null;
}
// planta_name is '' when the last import had no Planta name, and null when unknown because the plant was imported before names were stored.
export type PlantaNames = { nickname: string | null; planta_name: string | null };
// Planta's name replaces the nickname only when it changed since the last import, so a Fytorium rename stays.
export function namesAfterImport(plantaName: string | null, plant?: PlantaNames): PlantaNames {
  if (!plant) return { nickname: plantaName, planta_name: plantaName ?? '' };
  if (plantaName === null) return plant;
  return { nickname: plant.planta_name === null || plant.planta_name === plantaName ? plant.nickname : plantaName, planta_name: plantaName };
}
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
  const localized = text(raw.names?.localizedName);
  const scientific = text(raw.names?.scientific);
  const custom = text(raw.names?.custom);
  return { id: raw.id, mapped: {
    nickname: null, custom_name: custom, localized_name: localized, cultivar: text(raw.names?.variety), scientific_name: scientific ?? localized ?? '',
    site_name: text(raw.site?.name) ?? 'Imported plants',
    pot_type: Object.hasOwn(pots, potType) ? pots[potType] : 'other', soil_type: Object.hasOwn(soils, soilType) ? soils[soilType] : 'other',
    pot_size_cm: Number.isInteger(pot?.size) && pot.size > 0 && pot.size <= 2147483647 ? pot.size : null,
    care, cover_url: typeof raw.image?.url === 'string' && raw.image.url.length <= 4096 ? raw.image.url : null,
  } };
}
export type StoredImportStaging = Omit<ImportStaging, 'mapped' | 'can_commit' | 'can_review' | 'cover_status'> & { mapped: ImportedFields; suggestion: ImportSuggestion | null; identified_at: Date | null };
export async function storedStaging(household: string): Promise<StoredImportStaging[]> {
  const result = await db().query('SELECT id, planta_plant_id, mapped, candidates, selected, status, plant_id, cover_error, suggestion, identified_at FROM import_staging WHERE household_id = $1 ORDER BY planta_plant_id', [household]);
  return result.rows;
}
export async function staging(household: string, language: CommonNameLanguage): Promise<ImportStaging[]> {
  const rows = await storedStaging(household);
  const named = await withCommonNames(rows.flatMap(row => [...row.candidates, ...(row.selected ? [row.selected] : [])]), language);
  const canCommit = !rows.some(row => row.status === 'review') && rows.some(row => row.status === 'confirmed');
  const imported = await db().query<PlantaNames & { planta_plant_id: string; planta_cover_imported: boolean; status: string; display_name: string }>(
    'SELECT planta_plant_id, planta_cover_imported, nickname, planta_name, status, plant_display_name(nickname, gbif_key, $2) AS display_name FROM plants WHERE household_id = $1 AND planta_plant_id IS NOT NULL',
    [household, language]);
  const plants = new Map(imported.rows.map(plant => [plant.planta_plant_id, plant]));
  const stored = await db().query<Pick<Candidate, 'gbif_key' | 'scientific_name' | 'canonical_name'>>('SELECT gbif_key, scientific_name, canonical_name FROM species WHERE gbif_key = ANY($1::integer[])',
    [rows.flatMap(row => row.selected ? [row.selected.gbif_key] : [])]);
  const species = new Map(stored.rows.map(row => [row.gbif_key, row]));
  return rows.map(row => {
    const { suggestion, identified_at, ...publicRow } = row;
    const { cover_url, custom_name, localized_name, ...mapped } = row.mapped;
    const plant = plants.get(row.planta_plant_id);
    const coverStatus: ImportStaging['cover_status'] = plant?.planta_cover_imported ? 'imported'
      : row.cover_error ? 'failed' : !cover_url ? 'none' : ['skipped', 'committed'].includes(row.status) ? 'skipped' : 'pending';
    const selected = row.selected ? named(row.selected) : null;
    // Commit keeps an existing species' stored taxonomy, so the plant gets that canonical name.
    const taxonomy = selected && (species.get(selected.gbif_key) ?? selected);
    // Commit skips a graveyard plant, so it keeps its current name.
    const displayName = plant?.status === 'graveyard' ? plant.display_name
      : namesAfterImport(importNickname(row.mapped, ...[selected, taxonomy].filter(item => item !== null)), plant).nickname
        ?? selected?.common_name ?? taxonomy?.canonical_name ?? row.mapped.scientific_name;
    return { ...publicRow, candidates: row.candidates.map(named), selected,
      mapped: { ...mapped, has_cover: Boolean(cover_url) }, display_name: displayName,
      can_commit: canCommit, can_review: row.status !== 'committed', cover_status: coverStatus };
  });
}
export async function stageImport(code: string, household: string, language: CommonNameLanguage) {
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
        const search = await searchSpecies(plant.mapped.scientific_name, household, language);
        candidates = search.candidates;
        const exact = candidates.find(candidate => !candidate.requires_confirmation);
        if (exact) selected = chosenSpecies(search.search_id, exact.gbif_key, household, false);
      }
      catch (cause) { if (!isHttpError(cause) || ![422, 503].includes(cause.status)) throw cause; }
    }
    plant.mapped.nickname = importNickname(plant.mapped, ...(selected ? [selected] : []));
    mappedRows.push({ id: plant.id, mapped: plant.mapped, candidates, selected });
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
  return staging(household, language);
}

export async function reviewImport(id: string, household: string, input: Record<string, unknown>, language: CommonNameLanguage) {
  if (!isUuid(id)) error(404, 'Import plant not found');
  await withTransaction(async (client) => {
    await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
    const result = await client.query<Pick<StoredImportStaging, 'status' | 'candidates' | 'suggestion'>>('SELECT status, candidates, suggestion FROM import_staging WHERE id = $1 AND household_id = $2 FOR UPDATE', [id, household]);
    const row = result.rows[0];
    if (!row) error(404, 'Import plant not found');
    if (row.status === 'committed') error(409, 'This plant is already imported. Fetch again to update it.');
    let selected = null;
    let status = 'skipped';
    if (input.skip !== true) {
      selected = input.search_id !== undefined
        ? chosenSpecies(input.search_id, input.gbif_key, household, input.confirmed)
        : chosenSpecies(recordSpeciesSearch([...row.candidates, ...(row.suggestion?.candidates ?? []), ...(row.suggestion?.genus ? [row.suggestion.genus] : [])], household).search_id, input.gbif_key, household, input.confirmed);
      if (!selected) error(422, 'Choose a species from the candidates or search again.');
      if (selected.requires_confirmation && input.confirmed !== true) error(422, 'Confirm this species before importing.');
      status = 'confirmed';
    }
    await client.query('UPDATE import_staging SET selected = $1, status = $2 WHERE id = $3 AND household_id = $4', [selected ? JSON.stringify(selected) : null, status, id, household]);
  });
  return (await staging(household, language)).find(row => row.id === id)!;
}

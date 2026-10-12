import { randomUUID } from 'node:crypto';
import { error, isHttpError } from '@sveltejs/kit';
import type { Identity } from '../auth';
import { db, withTransaction } from '../db';
import { config } from '../config';
import { now, calendarDate } from '../clock';
import { sanitiseImage } from '../images';
import { removePhotos } from '../photo-storage';
import { photoThumbnail, storePhotoPair } from '../photos';
import { downloadCover } from './cover';
import { saveSpecies } from '../species';
import { kickNameFetch } from '../names';
import { staging, storedStaging, type StoredImportStaging } from './import';

export async function commitImport(identity: Identity) {
  const household = identity.household.id;
  const rows = await storedStaging(household);
  if (rows.some(row => row.status === 'review')) error(422, 'Confirm a species or skip every plant before importing.');
  for (const candidate of rows.filter(row => row.status === 'confirmed')) {
    let original: Buffer | undefined;
    let thumb: Buffer | undefined;
    let coverError: string | null = null;
    const previous = await db().query('SELECT status, planta_cover_imported FROM plants WHERE household_id = $1 AND planta_plant_id = $2', [household, candidate.planta_plant_id]);
    // Download, decode and resize before holding the household lock. A bad cover must not lose the plant.
    if (candidate.mapped.cover_url && !previous.rows[0]?.planta_cover_imported && previous.rows[0]?.status !== 'graveyard') {
      try {
        original = await sanitiseImage(await downloadCover(candidate.mapped.cover_url));
        thumb = await photoThumbnail(original);
      }
      catch (cause) { coverError = isHttpError(cause) ? cause.body.message : 'Cover image could not be decoded.'; }
    }
    const photoKeys: string[] = [];
    try {
      await withTransaction(async (client) => {
        // Serializes site matching, competing commits, review and staging replacement.
        await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
        const result = await client.query('SELECT * FROM import_staging WHERE id = $1 AND household_id = $2 FOR UPDATE', [candidate.id, household]);
        const row = result.rows[0] as StoredImportStaging | undefined;
        if (!row) error(409, 'The import was replaced. Reload the review before committing.');
        if (row.status !== 'confirmed') { return; }
        if (row.mapped.cover_url !== candidate.mapped.cover_url) error(409, 'The import changed. Reload the review before committing.');
        if (!row.selected) error(422, 'Confirm a species before importing.');
        const unreviewed = await client.query("SELECT id FROM import_staging WHERE household_id = $1 AND status = 'review' LIMIT 1", [household]);
        if (unreviewed.rowCount) error(422, 'Confirm a species or skip every plant before importing.');
        // Share the care/lifecycle plant lock. Re-import never changes a graveyard plant.
        const existing = await client.query('SELECT id, status FROM plants WHERE household_id = $1 AND planta_plant_id = $2 FOR UPDATE', [household, row.planta_plant_id]);
        if (existing.rows[0]?.status === 'graveyard') {
          await client.query("UPDATE import_staging SET status = 'skipped', plant_id = $1 WHERE id = $2 AND household_id = $3", [existing.rows[0].id, row.id, household]);
          return;
        }
        const fields = row.mapped;
        for (const date of Object.values(fields.care)) if (date.length === 10 ? date > calendarDate(now()) : new Date(date).getTime() > now().getTime()) error(422, 'Planta supplied a future last care date.');
        const site = await client.query('SELECT id FROM sites WHERE household_id = $1 AND lower(btrim(name)) = lower($2) ORDER BY created_at, id LIMIT 1', [household, fields.site_name]);
        let siteId = site.rows[0]?.id;
        if (!siteId) {
          const added = await client.query(`INSERT INTO sites (household_id, name, location, light, grow_light, humidity, draft, imported_conditions_incomplete)
            VALUES ($1,$2,'indoor','part_sun',false,'normal',false,true) RETURNING id`, [household, fields.site_name]);
          siteId = added.rows[0].id;
        }
        await saveSpecies(client, row.selected, true);
        // A new fetch replaces the Planta-sourced fields, including owner edits. Other fields stay local.
        const plant = await client.query(`INSERT INTO plants (household_id, planta_plant_id, gbif_key, site_id, nickname, cultivar, pot_type, pot_size_cm, soil_type, attributes_as_of, created_at)
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
          ON CONFLICT (household_id, planta_plant_id) DO UPDATE SET gbif_key = EXCLUDED.gbif_key, site_id = EXCLUDED.site_id,
            nickname = EXCLUDED.nickname, cultivar = EXCLUDED.cultivar, pot_type = EXCLUDED.pot_type, pot_size_cm = EXCLUDED.pot_size_cm, soil_type = EXCLUDED.soil_type
          RETURNING id, planta_cover_imported`,
          [household, row.planta_plant_id, row.selected.gbif_key, siteId, fields.nickname, fields.cultivar, fields.pot_type, fields.pot_size_cm, fields.soil_type, now().toISOString().slice(0, 10), now()]);
        const plantId = plant.rows[0].id;
        for (const [task, date] of Object.entries(fields.care)) {
          await client.query(`INSERT INTO care_events (household_id, plant_id, task_type, kind, occurred_at, by_member, by_name, request_id, batch_id, source)
            VALUES ($1,$2,$3,'completed',CASE WHEN length($4::text) = 10 THEN $4::date::timestamp AT TIME ZONE $7 ELSE $4::timestamptz END,$5,'Planta import',$6,NULL,'planta')
            ON CONFLICT (household_id, plant_id, task_type) WHERE source = 'planta' DO UPDATE SET occurred_at = EXCLUDED.occurred_at`,
            [household, plantId, task, date, identity.member.id, randomUUID(), config().householdTimezone]);
        }
        if (original && thumb && !plant.rows[0].planta_cover_imported) {
          const photoId = randomUUID();
          const prefix = `photos/${household}/${plantId}/${photoId}`;
          photoKeys.push(prefix + '-original.jpg', prefix + '-thumb.jpg');
          await storePhotoPair(photoKeys, original, thumb);
          await client.query('UPDATE photo_updates SET is_cover = false WHERE plant_id = $1 AND household_id = $2 AND is_cover', [plantId, household]);
          const today = calendarDate(now());
          await client.query(`INSERT INTO photo_updates (id, household_id, plant_id, original_key, thumb_key, taken_on, validated_on, created_by, created_at, is_cover, source)
            VALUES ($1,$2,$3,$4,$5,$6,$6,$7,$8,true,'planta')`, [photoId, household, plantId, ...photoKeys, today, identity.member.id, now()]);
          await client.query('UPDATE plants SET planta_cover_imported = true WHERE id = $1 AND household_id = $2', [plantId, household]);
        }
        await client.query("UPDATE import_staging SET status = 'committed', plant_id = $1, cover_error = $4 WHERE id = $2 AND household_id = $3", [plantId, row.id, household, plant.rows[0].planta_cover_imported ? null : coverError]);
      });
    } catch (cause) {
      if (photoKeys.length) {
        try { await removePhotos(photoKeys); } catch { console.error('Planta import photo cleanup failed.'); }
      }
      throw cause;
    }
  }
  kickNameFetch();
  return staging(household);
}

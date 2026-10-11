import { randomUUID } from 'node:crypto';
import sharp from 'sharp';
import { error } from '@sveltejs/kit';
import type { Identity } from '../auth';
import { db } from '../db';
import { now } from '../clock';
import { calendarDate } from '../tasks';
import { sanitiseImage } from '../images';
import { storePhoto, removePhotos } from '../photo-storage';
import { downloadCover } from './cover';
import { saveSpecies } from '../species';
import { kickNameFetch } from '../names';
import { staging } from './import';
import type { ImportStaging } from '$lib/import';

export async function commitImport(identity: Identity) {
  const household = identity.household.id;
  const rows = await staging(household);
  if (rows.some(row => row.status === 'review')) error(422, 'Confirm a species or skip every plant before importing.');
  for (const candidate of rows.filter(row => row.status === 'confirmed')) {
    const client = await db().connect();
    const photoKeys: string[] = [];
    try {
      await client.query('BEGIN');
      // Serializes site matching, competing commits, review and staging replacement.
      await client.query('SELECT id FROM households WHERE id = $1 FOR UPDATE', [household]);
      const result = await client.query('SELECT * FROM import_staging WHERE id = $1 AND household_id = $2 FOR UPDATE', [candidate.id, household]);
      const row = result.rows[0] as ImportStaging | undefined;
      if (!row) error(409, 'The import was replaced. Reload the review before committing.');
      if (row.status !== 'confirmed') { await client.query('COMMIT'); continue; }
      if (!row.selected) error(422, 'Confirm a species before importing.');
      const unreviewed = await client.query("SELECT id FROM import_staging WHERE household_id = $1 AND status = 'review' LIMIT 1", [household]);
      if (unreviewed.rowCount) error(422, 'Confirm a species or skip every plant before importing.');
      // Share the care/lifecycle plant lock. Re-import never changes a graveyard plant.
      const existing = await client.query('SELECT id, status FROM plants WHERE household_id = $1 AND planta_plant_id = $2 FOR UPDATE', [household, row.planta_plant_id]);
      if (existing.rows[0]?.status === 'graveyard') {
        await client.query("UPDATE import_staging SET status = 'skipped', plant_id = $1 WHERE id = $2 AND household_id = $3", [existing.rows[0].id, row.id, household]);
        await client.query('COMMIT');
        continue;
      }
      const fields = row.mapped;
      for (const date of Object.values(fields.care)) if (new Date(date).getTime() > now().getTime()) error(422, 'Planta supplied a future last care date.');
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
          VALUES ($1,$2,$3,'completed',$4,$5,'Planta import',$6,NULL,'planta')
          ON CONFLICT (household_id, plant_id, task_type) WHERE source = 'planta' DO UPDATE SET occurred_at = EXCLUDED.occurred_at`,
          [household, plantId, task, date, identity.member.id, randomUUID()]);
      }
      if (fields.cover_url && !plant.rows[0].planta_cover_imported) {
        const original = await sanitiseImage(await downloadCover(fields.cover_url));
        const thumb = await sharp(original).resize(400, 400, { fit: 'inside', withoutEnlargement: true }).jpeg({ quality: 80 }).toBuffer();
        const photoId = randomUUID();
        const prefix = `photos/${household}/${plantId}/${photoId}`;
        photoKeys.push(prefix + '-original.jpg', prefix + '-thumb.jpg');
        await storePhoto(photoKeys[0], original);
        await storePhoto(photoKeys[1], thumb);
        await client.query('UPDATE photo_updates SET is_cover = false WHERE plant_id = $1 AND household_id = $2 AND is_cover', [plantId, household]);
        const today = calendarDate(now());
        await client.query(`INSERT INTO photo_updates (id, household_id, plant_id, original_key, thumb_key, taken_on, validated_on, created_by, created_at, is_cover, source)
          VALUES ($1,$2,$3,$4,$5,$6,$6,$7,$8,true,'planta')`, [photoId, household, plantId, ...photoKeys, today, identity.member.id, now()]);
        await client.query('UPDATE plants SET planta_cover_imported = true WHERE id = $1 AND household_id = $2', [plantId, household]);
      }
      await client.query("UPDATE import_staging SET status = 'committed', plant_id = $1 WHERE id = $2 AND household_id = $3", [plantId, row.id, household]);
      await client.query('COMMIT');
    } catch (cause) {
      try { await client.query('ROLLBACK'); } catch { console.error('Planta import rollback failed.'); }
      if (photoKeys.length) {
        try { await removePhotos(photoKeys); } catch { console.error('Planta import photo cleanup failed.'); }
      }
      throw cause;
    }
    finally { client.release(); }
  }
  kickNameFetch();
  return staging(household);
}

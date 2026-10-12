import { db } from '../db';
import { now } from '../clock';
import type { PlantbookMatch } from './open-plantbook';

export async function recordPlantbookMatch(key: number, match: PlantbookMatch) {
  await db().query('INSERT INTO open_plantbook_matches (gbif_key, status, pid, checked_at) VALUES ($1, $2, $3, $4) ON CONFLICT (gbif_key) DO UPDATE SET status = EXCLUDED.status, pid = EXCLUDED.pid, checked_at = EXCLUDED.checked_at', [key, match.status, match.pid, now()]);
}

import { error } from '@sveltejs/kit';
import { db } from './db';
import { now } from './clock';
import { referencePhoto } from './gbif';
import { gbifNames } from './name-sources/gbif';
import type { FetchedName } from './name-sources/shared';
import { downloadImage } from './net/download-image';
import { sanitiseImage } from './images';

type SpeciesPhoto = { thumbnail: Buffer | null; creator: string | null; license: 'CC BY 4.0' | 'CC0' | null; source_url: string | null; common_names: FetchedName[] };
const RETRY_CACHE_MS = 3600000;
const pending = new Map<number, Promise<SpeciesPhoto>>();

export async function speciesPhoto(value: string): Promise<SpeciesPhoto> {
  if (!/^[1-9]\d*$/.test(value) || Number(value) > 2147483647) error(422, 'Choose a valid GBIF key.');
  const key = Number(value);
  const cached = await db().query<SpeciesPhoto>('SELECT thumbnail, creator, license, source_url, common_names FROM species_photos WHERE gbif_key = $1 AND expires_at > $2', [key, now()]);
  if (cached.rowCount) return cached.rows[0];
  const existing = pending.get(key);
  if (existing) return existing;
  const loading = fetchPhoto(key);
  pending.set(key, loading);
  try { return await loading; }
  finally { pending.delete(key); }
}

async function fetchPhoto(key: number): Promise<SpeciesPhoto> {
  let lifetime = 30 * 86400000;
  const [reference, common_names] = await Promise.all([
    referencePhoto(key).catch(() => { lifetime = RETRY_CACHE_MS; return null; }),
    gbifNames.fetchNames({ gbif_key: key, canonical_name: '' }).catch(() => { lifetime = RETRY_CACHE_MS; return [] as FetchedName[]; }),
  ]);
  const photo: SpeciesPhoto = { thumbnail: null, creator: null, license: null, source_url: null, common_names };
  if (reference) {
    try {
      photo.thumbnail = await sanitiseImage(await downloadImage(reference.identifier, 'Reference'), 400);
      photo.creator = reference.creator; photo.license = reference.license; photo.source_url = reference.source_url;
    } catch { lifetime = RETRY_CACHE_MS; }
  }
  await db().query(`INSERT INTO species_photos (gbif_key, thumbnail, creator, license, source_url, common_names, expires_at)
    VALUES ($1,$2,$3,$4,$5,$6,$7) ON CONFLICT (gbif_key) DO UPDATE SET
    thumbnail = EXCLUDED.thumbnail, creator = EXCLUDED.creator, license = EXCLUDED.license,
    source_url = EXCLUDED.source_url, common_names = EXCLUDED.common_names, expires_at = EXCLUDED.expires_at`,
  [key, photo.thumbnail, photo.creator, photo.license, photo.source_url, JSON.stringify(common_names), new Date(now().getTime() + lifetime)]);
  return photo;
}

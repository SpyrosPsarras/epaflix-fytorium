import type { Db } from './db';
import type { Api } from '../api/client';
import { memberKey, sameMember, type Member } from './cache';

export type Variant = 'thumb' | 'display';
// Files under one folder per member and role; the app uses expo-file-system, Jest a map.
export type Disk = {
  uri(folder: string, name: string): string | null;
  write(folder: string, name: string, base64: string): Promise<void>;
  remove(folder: string, name: string): void;
  removeAll(): void;
};

const CAP_BYTES = 50 * 1024 * 1024;
const ENTRY_BYTES = 1024 * 1024;
const SAFE_ID = /^[A-Za-z0-9_-]+$/;
const decodedSize = (base64: string) => Math.floor((base64.length * 3) / 4) - (base64.endsWith('==') ? 2 : base64.endsWith('=') ? 1 : 0);

// N2: only thumb and display copies reach disk, 1 MiB each, 50 MiB in all, least recently used first out; originals never.
export class PhotoCache {
  private generation = 0;
  private clock: number;

  constructor(
    private readonly db: Db,
    private readonly disk: Disk,
    private readonly api: Pick<Api, 'request'>,
    private readonly online: () => boolean,
    // Only the member the cache is bound to may read or write photos; a page left over from before a switch gets nothing.
    private readonly bound: () => Member | null,
    private readonly capBytes = CAP_BYTES,
  ) {
    this.clock = db.get<{ last: number | null }>('SELECT MAX(used_at) AS last FROM photo_cache')?.last ?? 0;
  }

  async load(member: Member, photoId: string, variant: Variant = 'thumb'): Promise<string | null> {
    if (!SAFE_ID.test(photoId) || (variant !== 'thumb' && variant !== 'display')) throw new Error('Invalid photo');
    if (!sameMember(this.bound(), member)) return null;
    const folder = memberKey(member);
    const key = `${folder}/${photoId}/${variant}`;
    const name = `${photoId}.${variant}.jpg`;
    const cached = this.db.get<{ file: string }>('SELECT file FROM photo_cache WHERE key = ?', key);
    const uri = cached ? this.disk.uri(folder, name) : null;
    if (uri) {
      this.db.run('UPDATE photo_cache SET used_at = ? WHERE key = ?', ++this.clock, key);
      return uri;
    }
    if (!this.online()) return null;
    const epoch = this.generation;
    let body: string;
    try {
      const response = await this.api.request(`/api/photos/${photoId}/${variant}`, { binary: true });
      if (response.status < 200 || response.status > 299) return null;
      body = response.body;
    } catch {
      return null;
    }
    const size = decodedSize(body);
    if (size > ENTRY_BYTES || size > this.capBytes || epoch !== this.generation) return null;
    await this.disk.write(folder, name, body);
    // A wipe or a member change during the download belongs to an ended member; the file goes with it.
    if (epoch !== this.generation || !sameMember(this.bound(), member)) { this.disk.remove(folder, name); return null; }
    this.db.run('INSERT OR REPLACE INTO photo_cache (key, file, size, used_at) VALUES (?, ?, ?, ?)', key, `${folder}/${name}`, size, ++this.clock);
    this.trim();
    return this.disk.uri(folder, name);
  }

  totalBytes(): number {
    return this.db.get<{ total: number | null }>('SELECT SUM(size) AS total FROM photo_cache')?.total ?? 0;
  }

  private trim() {
    let total = this.totalBytes();
    for (const row of this.db.all<{ key: string; file: string; size: number }>('SELECT key, file, size FROM photo_cache ORDER BY used_at')) {
      if (total <= this.capBytes) break;
      const [folder, name] = row.file.split('/');
      this.disk.remove(folder, name);
      this.db.run('DELETE FROM photo_cache WHERE key = ?', row.key);
      total -= row.size;
    }
  }

  wipe() {
    this.generation += 1;
    this.disk.removeAll();
    this.db.run('DELETE FROM photo_cache');
  }
}

export function fileDisk(): Disk {
  const { Directory, File, Paths } = require('expo-file-system') as typeof import('expo-file-system');
  const root = new Directory(Paths.cache, 'photos');
  const folderOf = (folder: string) => new Directory(root, folder);
  return {
    uri(folder, name) {
      const file = new File(folderOf(folder), name);
      return file.info().exists ? file.uri : null;
    },
    async write(folder, name, base64) {
      const directory = folderOf(folder);
      directory.create({ intermediates: true, idempotent: true });
      const file = new File(directory, name);
      file.create({ overwrite: true });
      await file.write(base64, { encoding: 'base64' });
    },
    remove(folder, name) {
      const file = new File(folderOf(folder), name);
      if (file.info().exists) file.delete();
    },
    removeAll() {
      if (root.info().exists) root.delete();
    },
  };
}

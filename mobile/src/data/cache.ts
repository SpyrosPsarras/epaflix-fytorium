import type { Db } from './db';

export type Role = 'owner' | 'family' | 'sitter';
export type Member = { id: string; role: Role };
export const sameMember = (a: Member | null, b: Member | null) => a?.id === b?.id && a?.role === b?.role;
// Folder and key names for one member in one role: a role change starts a fresh cache.
export const memberKey = (member: Member) => `${member.id}.${member.role}`;

export function parseMember(identity: string): Member {
  const member = JSON.parse(identity)?.member;
  if (typeof member?.id !== 'string' || !['owner', 'family', 'sitter'].includes(member?.role)) throw new Error('Invalid identity');
  return { id: member.id, role: member.role };
}

// JSON snapshots keep server groups and provenance; every row belongs to the bound member, as Room's CachedCare did.
export class CareCache {
  constructor(private readonly db: Db) {}

  identity(): string | null {
    return this.db.get<{ json: string }>("SELECT json FROM cached_care WHERE resource = 'identity' LIMIT 1")?.json ?? null;
  }

  member(): Member | null {
    const identity = this.identity();
    try { return identity ? parseMember(identity) : null; } catch { return null; }
  }

  // Binds the cache to this identity; a different member or role wipes it first. Returns whether it changed.
  bind(identity: string): boolean {
    const member = parseMember(identity);
    let changed = false;
    this.db.transaction(() => {
      changed = !sameMember(this.member(), member);
      if (changed) this.db.run('DELETE FROM cached_care');
      this.db.run("DELETE FROM cached_care WHERE resource = 'identity'");
      this.db.run("INSERT INTO cached_care (member_id, resource, json, refreshed_at) VALUES (?, 'identity', ?, 0)", member.id, identity);
    });
    return changed;
  }

  get(member: Member, resource: string): { json: string; refreshedAt: number } | null {
    if (!sameMember(this.member(), member)) return null;
    const row = this.db.get<{ json: string; refreshed_at: number }>(
      'SELECT json, refreshed_at FROM cached_care WHERE member_id = ? AND resource = ?', member.id, resource);
    return row ? { json: row.json, refreshedAt: row.refreshed_at } : null;
  }

  // A response can outlive the binding that started it, so a write for anyone but the bound member is dropped.
  save(member: Member, snapshots: Record<string, string>, time: number): boolean {
    let saved = false;
    this.db.transaction(() => {
      if (!sameMember(this.member(), member)) return;
      for (const [resource, json] of Object.entries(snapshots)) {
        this.db.run('INSERT OR REPLACE INTO cached_care (member_id, resource, json, refreshed_at) VALUES (?, ?, ?, ?)', member.id, resource, json, time);
      }
      saved = true;
    });
    return saved;
  }

  wipe() {
    this.db.run('DELETE FROM cached_care');
  }
}

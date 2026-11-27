import type { Db } from './db';

// Unsaved page state, kept so Android can bring it back after it ended the process. Keys carry the member and role.
export class Drafts {
  constructor(private readonly db: Db) {}

  get<T>(key: string): T | null {
    const row = this.db.get<{ json: string }>('SELECT json FROM drafts WHERE key = ?', key);
    if (!row) return null;
    try { return JSON.parse(row.json) as T; } catch { return null; }
  }

  put(key: string, value: unknown) {
    this.db.run('INSERT OR REPLACE INTO drafts (key, json) VALUES (?, ?)', key, JSON.stringify(value));
  }

  remove(key: string) {
    this.db.run('DELETE FROM drafts WHERE key = ?', key);
  }

  // Everything under a page route, which leaves the stack with it.
  removeUnder(prefix: string) {
    this.db.run("DELETE FROM drafts WHERE key = ? OR key LIKE ? ESCAPE '\\'", prefix, prefix.replace(/[\\%_]/g, '\\$&') + '/%');
  }

  any(): boolean {
    return this.db.get('SELECT 1 AS found FROM drafts LIMIT 1') !== null;
  }

  wipe() {
    this.db.run('DELETE FROM drafts');
  }
}

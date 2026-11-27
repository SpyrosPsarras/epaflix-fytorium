import { migrate, type Db } from '../../data/db';

// The same SQL against Node's built-in SQLite.
export function nodeDb(): Db {
  const { DatabaseSync } = require('node:sqlite') as { DatabaseSync: new (path: string) => any };
  const sqlite = new DatabaseSync(':memory:');
  let depth = 0;
  const db: Db = {
    exec: (sql) => sqlite.exec(sql),
    run: (sql, ...params) => { sqlite.prepare(sql).run(...params); },
    get: (sql, ...params) => (sqlite.prepare(sql).get(...params) as never) ?? null,
    all: (sql, ...params) => sqlite.prepare(sql).all(...params) as never,
    transaction(task) {
      if (depth > 0) { task(); return; }
      depth += 1;
      sqlite.exec('BEGIN');
      try { task(); sqlite.exec('COMMIT'); } catch (error) { sqlite.exec('ROLLBACK'); throw error; } finally { depth -= 1; }
    },
  };
  migrate(db);
  return db;
}

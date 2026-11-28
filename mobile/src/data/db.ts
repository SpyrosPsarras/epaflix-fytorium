export type Param = string | number | null;

// The few SQLite calls the app needs; expo-sqlite in the app, node:sqlite in Jest.
export type Db = {
  exec(sql: string): void;
  run(sql: string, ...params: Param[]): void;
  get<T>(sql: string, ...params: Param[]): T | null;
  all<T>(sql: string, ...params: Param[]): T[];
  transaction(task: () => void): void;
};

const SCHEMA = `
CREATE TABLE IF NOT EXISTS cached_care (member_id TEXT NOT NULL, resource TEXT NOT NULL, json TEXT NOT NULL, refreshed_at INTEGER NOT NULL,
  PRIMARY KEY (member_id, resource));
CREATE TABLE IF NOT EXISTS drafts (key TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS photo_cache (key TEXT PRIMARY KEY NOT NULL, file TEXT NOT NULL, size INTEGER NOT NULL, used_at INTEGER NOT NULL);
`;

export function migrate(db: Db) {
  db.exec(SCHEMA);
}

export function openAppDb(): Db {
  // Required lazily so Jest never loads the native module.
  const { openDatabaseSync } = require('expo-sqlite') as typeof import('expo-sqlite');
  const sqlite = openDatabaseSync('fytorium.db');
  const db: Db = {
    exec: (sql) => sqlite.execSync(sql),
    run: (sql, ...params) => { sqlite.runSync(sql, ...params); },
    get: (sql, ...params) => sqlite.getFirstSync(sql, ...params),
    all: (sql, ...params) => sqlite.getAllSync(sql, ...params),
    transaction: (task) => sqlite.withTransactionSync(task),
  };
  migrate(db);
  return db;
}

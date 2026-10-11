import pg from 'pg';
import { config } from './config';

let pool: pg.Pool | undefined;
export function db(): pg.Pool {
  if (!pool) {
    pool = new pg.Pool({ connectionString: config().databaseUrl, max: 10, connectionTimeoutMillis: 5000, statement_timeout: 10000 });
    pool.on('error', () => console.error('An idle Postgres connection closed.'));
  }
  return pool;
}

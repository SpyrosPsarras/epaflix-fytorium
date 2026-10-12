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

export async function withTransaction<T>(fn: (client: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await db().connect();
  let connectionError: Error | undefined;
  let discard = false;
  const onError = (cause: Error) => { connectionError = cause; discard = true; };
  client.on('error', onError);
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    if (connectionError) throw connectionError;
    try { await client.query('COMMIT'); }
    catch (cause) { discard = true; throw cause; }
    if (connectionError) throw connectionError;
    return result;
  } catch (cause) {
    try { await client.query('ROLLBACK'); }
    catch { discard = true; console.error('Postgres transaction rollback failed; discarding the connection.'); }
    throw cause;
  } finally {
    if (discard) client.release(true);
    else client.release();
    client.removeListener('error', onError);
  }
}

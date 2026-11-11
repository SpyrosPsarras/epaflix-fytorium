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

export async function withTransaction<T>(fn: (client: pg.PoolClient) => Promise<T>, begin = 'BEGIN'): Promise<T> {
  const client = await db().connect();
  let connectionError: Error | undefined;
  let discard = false;
  const onError = (cause: Error) => { connectionError = cause; discard = true; };
  client.on('error', onError);
  try {
    await client.query(begin);
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

// Every read sees one snapshot. Postgres refuses (40001) a write to a row that changed since then, so the work runs once more on a fresh snapshot.
export async function withSnapshot<T>(fn: (client: pg.PoolClient) => Promise<T>): Promise<T> {
  const run = () => withTransaction(fn, 'BEGIN ISOLATION LEVEL REPEATABLE READ');
  return run().catch(cause => { if ((cause as { code?: string } | undefined)?.code === '40001') return run(); throw cause; });
}

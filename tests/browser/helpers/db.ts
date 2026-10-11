import pg from 'pg';

export async function insertPlantForSite(siteName: string, nickname: string) {
  const client = new pg.Client({ connectionString: 'postgres://fytorium@postgres:5432/fytorium' });
  await client.connect();
  try {
    // No plant API exists until #5. SQL supplies synthetic fixtures only.
    const result = await client.query(
      `INSERT INTO plants (household_id, site_id, nickname)
       SELECT household_id, id, $2 FROM sites WHERE name = $1`,
      [siteName, nickname],
    );
    if (result.rowCount !== 1) throw new Error('Expected one synthetic site for the plant fixture');
  } finally {
    await client.end();
  }
}

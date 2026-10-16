-- migrate:up
CREATE TABLE species_photos (
  gbif_key integer PRIMARY KEY CHECK (gbif_key > 0),
  thumbnail bytea,
  creator text,
  license text CHECK (license IN ('CC BY 4.0', 'CC0')),
  source_url text,
  common_names jsonb NOT NULL DEFAULT '[]',
  expires_at timestamptz NOT NULL,
  CHECK ((thumbnail IS NULL AND creator IS NULL AND license IS NULL AND source_url IS NULL)
    OR (thumbnail IS NOT NULL AND creator IS NOT NULL AND license IS NOT NULL AND source_url IS NOT NULL))
);

ALTER TABLE import_staging ADD COLUMN suggestion jsonb;
ALTER TABLE import_staging ADD COLUMN identified_at timestamptz;
ALTER TABLE import_staging ADD COLUMN cover_thumbnail bytea;

-- migrate:down
ALTER TABLE import_staging DROP COLUMN suggestion, DROP COLUMN identified_at, DROP COLUMN cover_thumbnail;
DROP TABLE species_photos;

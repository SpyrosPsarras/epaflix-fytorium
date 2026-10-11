-- migrate:up
CREATE TABLE open_plantbook_matches (
  gbif_key integer PRIMARY KEY REFERENCES species(gbif_key) ON DELETE CASCADE,
  status text NOT NULL CHECK (status IN ('matched', 'no_match', 'ambiguous')),
  pid text,
  checked_at timestamptz NOT NULL,
  CHECK ((status = 'matched' AND pid IS NOT NULL) OR (status <> 'matched' AND pid IS NULL))
);
INSERT INTO care_fetch_jobs (gbif_key, source, next_at)
SELECT gbif_key, 'open_plantbook', now() FROM species ON CONFLICT DO NOTHING;

-- migrate:down
DELETE FROM care_fetch_jobs WHERE source = 'open_plantbook';
DELETE FROM care_source_cache WHERE source = 'open_plantbook';
DELETE FROM care_source_requests WHERE source = 'open_plantbook';
DELETE FROM care_source_limits WHERE source = 'open_plantbook';
DROP TABLE open_plantbook_matches;

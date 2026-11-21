-- migrate:up
CREATE TABLE species_search_cache (
  query text PRIMARY KEY,
  candidates jsonb NOT NULL,
  expires_at timestamptz NOT NULL
);

-- migrate:down
DROP TABLE species_search_cache;

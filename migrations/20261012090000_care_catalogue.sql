-- migrate:up
CREATE TABLE care_source_cache (
  gbif_key integer NOT NULL REFERENCES species(gbif_key) ON DELETE CASCADE,
  source text NOT NULL,
  source_key text NOT NULL,
  fields jsonb NOT NULL,
  fetched_at timestamptz NOT NULL,
  PRIMARY KEY (gbif_key, source)
);
CREATE TABLE care_overrides (
  household_id uuid NOT NULL REFERENCES households(id) ON DELETE CASCADE,
  gbif_key integer NOT NULL REFERENCES species(gbif_key) ON DELETE CASCADE,
  field text NOT NULL,
  value jsonb NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (household_id, gbif_key, field)
);
CREATE TABLE care_fetch_jobs (
  gbif_key integer NOT NULL REFERENCES species(gbif_key) ON DELETE CASCADE,
  source text NOT NULL,
  source_key text,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'fetching', 'complete', 'not_available', 'failed')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_at timestamptz NOT NULL,
  lease_until timestamptz,
  claim_id uuid,
  PRIMARY KEY (gbif_key, source)
);
CREATE INDEX care_fetch_ready ON care_fetch_jobs(next_at) WHERE status IN ('pending', 'fetching');
CREATE TABLE care_source_limits (
  source text PRIMARY KEY,
  backoff_until timestamptz,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'auth_failed')),
  last_refresh_check timestamptz
);
CREATE TABLE care_source_requests (
  source text NOT NULL REFERENCES care_source_limits(source),
  requested_at timestamptz NOT NULL
);
CREATE INDEX care_requests_by_time ON care_source_requests(source, requested_at);

-- migrate:down
DROP TABLE care_source_requests;
DROP TABLE care_source_limits;
DROP TABLE care_fetch_jobs;
DROP TABLE care_overrides;
DROP TABLE care_source_cache;

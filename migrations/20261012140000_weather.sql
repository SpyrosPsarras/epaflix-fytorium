-- migrate:up
ALTER TABLE households ADD COLUMN latitude double precision,
  ADD COLUMN longitude double precision, ADD COLUMN location_label text,
  ADD COLUMN location_revision integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT household_location CHECK (
    (latitude IS NULL AND longitude IS NULL AND location_label IS NULL) OR
    (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 AND length(location_label) <= 200)
  );
CREATE TABLE weather_cache (
  household_id uuid NOT NULL REFERENCES households(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('forecast', 'climate')),
  location_revision integer NOT NULL,
  data jsonb, fetched_at timestamptz, attempted_at timestamptz,
  unavailable_since timestamptz,
  PRIMARY KEY (household_id, kind)
);
CREATE TABLE weather_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind text NOT NULL CHECK (kind IN ('low_temperature', 'rain')),
  household_id uuid NOT NULL REFERENCES households(id) ON DELETE CASCADE,
  plant_id uuid,
  FOREIGN KEY (plant_id, household_id) REFERENCES plants(id, household_id) ON DELETE CASCADE,
  starts_on date NOT NULL, ends_on date NOT NULL CHECK (ends_on >= starts_on),
  payload jsonb NOT NULL, created_at timestamptz NOT NULL,
  resolved_at timestamptz
);
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE UNIQUE INDEX weather_events_open_rain ON weather_events(household_id, starts_on)
  WHERE kind = 'rain' AND resolved_at IS NULL;
ALTER TABLE weather_events ADD CONSTRAINT weather_events_open_cold_spell
  EXCLUDE USING gist (plant_id WITH =, daterange(starts_on, ends_on, '[]') WITH &&)
  WHERE (kind = 'low_temperature' AND resolved_at IS NULL);
CREATE INDEX weather_events_open ON weather_events(household_id, plant_id) WHERE resolved_at IS NULL;
CREATE TABLE task_batches (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES households(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind = 'rain'), request_id uuid NOT NULL UNIQUE,
  by_member uuid NOT NULL REFERENCES members(id), created_at timestamptz NOT NULL,
  undone_at timestamptz, undone_by uuid REFERENCES members(id)
);
CREATE TABLE task_batch_requests (
  request_id uuid PRIMARY KEY,
  batch_id uuid NOT NULL REFERENCES task_batches(id) ON DELETE CASCADE,
  by_member uuid NOT NULL REFERENCES members(id)
);
ALTER TABLE weather_events ADD COLUMN batch_id uuid REFERENCES task_batches(id);
ALTER TABLE care_events ADD COLUMN batch_id uuid REFERENCES task_batches(id) ON DELETE CASCADE;
CREATE INDEX care_events_batch ON care_events(batch_id);

-- migrate:down
ALTER TABLE care_events DROP COLUMN batch_id;
DROP TABLE weather_events;
DROP TABLE task_batch_requests;
DROP TABLE task_batches;
DROP TABLE weather_cache;
ALTER TABLE households DROP CONSTRAINT household_location, DROP COLUMN latitude,
  DROP COLUMN longitude, DROP COLUMN location_label, DROP COLUMN location_revision;

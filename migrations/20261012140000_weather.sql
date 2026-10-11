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
-- Rain uses the same request identity and care events as other care actions.
ALTER TABLE care_actions ALTER COLUMN plant_id DROP NOT NULL;
ALTER TABLE care_actions ADD COLUMN batch_id uuid,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN undone_at timestamptz,
  ADD COLUMN undone_by uuid REFERENCES members(id) ON DELETE SET NULL;
ALTER TABLE care_actions ADD CONSTRAINT care_actions_request_household UNIQUE (request_id, household_id),
  ADD CONSTRAINT care_actions_action_plant CHECK ((action = 'rain') = (plant_id IS NULL)),
  ADD CONSTRAINT care_actions_rain_alias CHECK (batch_id IS NULL OR (action = 'rain' AND batch_id <> request_id)),
  ADD CONSTRAINT care_actions_batch_household FOREIGN KEY (batch_id, household_id)
    REFERENCES care_actions(request_id, household_id) ON DELETE NO ACTION;
-- Undo retains batches and their history; deleting a referenced batch must fail.
ALTER TABLE weather_events ADD COLUMN batch_id uuid,
  ADD CONSTRAINT weather_events_batch_household FOREIGN KEY (batch_id, household_id)
    REFERENCES care_actions(request_id, household_id) ON DELETE NO ACTION;
ALTER TABLE care_events ADD CONSTRAINT care_events_batch_household FOREIGN KEY (batch_id, household_id)
  REFERENCES care_actions(request_id, household_id) ON DELETE NO ACTION;

-- migrate:down
DROP TABLE weather_events;
-- Refuse rollback while household rain actions remain, rather than deleting care history.
ALTER TABLE care_actions ALTER COLUMN plant_id SET NOT NULL;
ALTER TABLE care_events DROP CONSTRAINT care_events_batch_household;
ALTER TABLE care_actions DROP CONSTRAINT care_actions_batch_household,
  DROP CONSTRAINT care_actions_rain_alias, DROP CONSTRAINT care_actions_action_plant,
  DROP CONSTRAINT care_actions_request_household;
ALTER TABLE care_actions DROP COLUMN batch_id, DROP COLUMN created_at,
  DROP COLUMN undone_at, DROP COLUMN undone_by;
DROP TABLE weather_cache;
ALTER TABLE households DROP CONSTRAINT household_location, DROP COLUMN latitude,
  DROP COLUMN longitude, DROP COLUMN location_label, DROP COLUMN location_revision;

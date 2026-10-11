-- migrate:up
ALTER TABLE members ADD COLUMN display_name text NOT NULL DEFAULT 'Member';
ALTER TABLE plants ADD CONSTRAINT plants_id_household_unique UNIQUE (id, household_id);
CREATE TABLE care_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES households(id),
  plant_id uuid NOT NULL,
  task_type text NOT NULL CHECK (task_type IN ('water')),
  kind text NOT NULL CHECK (kind IN ('completed')),
  occurred_at timestamptz NOT NULL,
  by_member uuid REFERENCES members(id) ON DELETE SET NULL,
  by_name text NOT NULL,
  undone_at timestamptz,
  undone_by uuid REFERENCES members(id) ON DELETE SET NULL,
  request_id uuid NOT NULL UNIQUE,
  FOREIGN KEY (plant_id, household_id) REFERENCES plants(id, household_id) ON DELETE CASCADE
);
CREATE INDEX care_events_history ON care_events(household_id, plant_id, occurred_at DESC);

-- migrate:down
DROP TABLE care_events;
ALTER TABLE plants DROP CONSTRAINT plants_id_household_unique;
ALTER TABLE members DROP COLUMN display_name;

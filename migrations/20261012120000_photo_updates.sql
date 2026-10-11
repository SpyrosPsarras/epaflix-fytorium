-- migrate:up
ALTER TABLE plants ADD COLUMN health text CHECK (health IN ('poor', 'fair', 'good', 'very_good'));
CREATE TABLE photo_updates (
  id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES households(id),
  plant_id uuid NOT NULL,
  original_key text NOT NULL UNIQUE,
  thumb_key text NOT NULL UNIQUE,
  note text NOT NULL DEFAULT '' CHECK (length(note) <= 5000),
  health text CHECK (health IN ('poor', 'fair', 'good', 'very_good')),
  taken_on date NOT NULL,
  validated_on date NOT NULL,
  is_cover boolean NOT NULL DEFAULT false,
  created_by uuid NOT NULL REFERENCES members(id),
  created_at timestamptz NOT NULL,
  CHECK (taken_on <= validated_on),
  FOREIGN KEY (plant_id, household_id) REFERENCES plants(id, household_id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX photo_updates_one_cover ON photo_updates(plant_id) WHERE is_cover;
CREATE INDEX photo_updates_journal ON photo_updates(household_id, taken_on DESC, created_at DESC);
CREATE INDEX photo_updates_plant ON photo_updates(plant_id, taken_on DESC, created_at DESC);

-- migrate:down
DROP TABLE photo_updates;
ALTER TABLE plants DROP COLUMN health;

-- migrate:up
ALTER TABLE care_events DROP CONSTRAINT care_events_task_type_check;
ALTER TABLE care_events ADD CONSTRAINT care_events_task_type_check
  CHECK (task_type IN ('water', 'fertilize', 'mist', 'clean_leaves', 'remove_dead', 'cut_back', 'repot'));
ALTER TABLE plants ADD COLUMN misting_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE households ADD COLUMN misting_enabled boolean NOT NULL DEFAULT true;
CREATE TABLE plant_task_intervals (
  plant_id uuid NOT NULL,
  household_id uuid NOT NULL,
  task_type text NOT NULL CHECK (task_type IN ('water', 'fertilize', 'mist', 'clean_leaves', 'remove_dead', 'cut_back', 'repot')),
  interval_days integer NOT NULL CHECK (interval_days BETWEEN 1 AND 365),
  PRIMARY KEY (plant_id, task_type),
  FOREIGN KEY (plant_id, household_id) REFERENCES plants(id, household_id) ON DELETE CASCADE
);

-- migrate:down
DROP TABLE plant_task_intervals;
ALTER TABLE households DROP COLUMN misting_enabled;
ALTER TABLE plants DROP COLUMN misting_enabled;
-- Refuse rollback with non-water history rather than silently deleting care records.
ALTER TABLE care_events DROP CONSTRAINT care_events_task_type_check;
ALTER TABLE care_events ADD CONSTRAINT care_events_task_type_check CHECK (task_type IN ('water'));

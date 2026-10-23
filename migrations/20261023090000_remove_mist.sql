-- migrate:up
DELETE FROM care_events WHERE task_type = 'mist';
DELETE FROM plant_task_intervals WHERE task_type = 'mist';
DELETE FROM care_actions WHERE action LIKE 'mist:%' OR (action = 'complete-type' AND payload->>'task_type' = 'mist');
UPDATE import_staging SET mapped = mapped #- '{care,mist}' WHERE mapped->'care' ? 'mist';
UPDATE care_source_cache SET fields = fields - 'humidityNeed' WHERE fields ? 'humidityNeed';
DELETE FROM care_overrides WHERE field = 'humidityNeed';
ALTER TABLE plants DROP COLUMN misting_enabled;
ALTER TABLE households DROP COLUMN misting_enabled;
ALTER TABLE care_events DROP CONSTRAINT care_events_task_type_check;
ALTER TABLE care_events ADD CONSTRAINT care_events_task_type_check
  CHECK (task_type IN ('water', 'fertilize', 'clean_leaves', 'remove_dead', 'cut_back', 'repot'));
ALTER TABLE plant_task_intervals DROP CONSTRAINT plant_task_intervals_task_type_check;
ALTER TABLE plant_task_intervals ADD CONSTRAINT plant_task_intervals_task_type_check
  CHECK (task_type IN ('water', 'fertilize', 'clean_leaves', 'remove_dead', 'cut_back', 'repot'));
ALTER TABLE care_events ADD CONSTRAINT care_events_id_household_unique UNIQUE (id, household_id);
ALTER TABLE care_events ADD COLUMN linked_event_id uuid,
  ADD CONSTRAINT care_events_linked_event_household FOREIGN KEY (linked_event_id, household_id)
  REFERENCES care_events(id, household_id) ON DELETE CASCADE;
CREATE INDEX care_events_linked_event ON care_events(linked_event_id) WHERE linked_event_id IS NOT NULL;

-- migrate:down
ALTER TABLE care_events DROP COLUMN linked_event_id;
ALTER TABLE care_events DROP CONSTRAINT care_events_id_household_unique;
ALTER TABLE plants ADD COLUMN misting_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE households ADD COLUMN misting_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE care_events DROP CONSTRAINT care_events_task_type_check;
ALTER TABLE care_events ADD CONSTRAINT care_events_task_type_check
  CHECK (task_type IN ('water', 'fertilize', 'mist', 'clean_leaves', 'remove_dead', 'cut_back', 'repot'));
ALTER TABLE plant_task_intervals DROP CONSTRAINT plant_task_intervals_task_type_check;
ALTER TABLE plant_task_intervals ADD CONSTRAINT plant_task_intervals_task_type_check
  CHECK (task_type IN ('water', 'fertilize', 'mist', 'clean_leaves', 'remove_dead', 'cut_back', 'repot'));

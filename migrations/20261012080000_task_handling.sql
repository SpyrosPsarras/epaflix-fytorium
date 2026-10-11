-- migrate:up
ALTER TABLE care_events DROP CONSTRAINT care_events_kind_check;
ALTER TABLE care_events ADD CONSTRAINT care_events_kind_check CHECK (kind IN ('completed', 'snoozed', 'skipped'));
-- A sequence orders commands even when the fake clock or real timestamps tie.
ALTER TABLE care_events ADD COLUMN event_order bigserial;
ALTER TABLE care_events ADD COLUMN batch_id uuid;
ALTER TABLE care_events ADD COLUMN previous_pot jsonb;
CREATE TABLE care_actions (
  request_id uuid PRIMARY KEY,
  household_id uuid NOT NULL REFERENCES households(id),
  plant_id uuid NOT NULL,
  by_member uuid REFERENCES members(id) ON DELETE SET NULL,
  action text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}',
  FOREIGN KEY (plant_id, household_id) REFERENCES plants(id, household_id) ON DELETE CASCADE
);
INSERT INTO care_actions (request_id, household_id, plant_id, by_member, action)
  SELECT request_id, household_id, plant_id, by_member, task_type || ':completed' FROM care_events;
CREATE INDEX care_events_batch ON care_events(batch_id) WHERE batch_id IS NOT NULL;
ALTER TABLE members ADD COLUMN task_sort text NOT NULL DEFAULT 'task' CHECK (task_sort IN ('task', 'plant', 'site'));
ALTER TABLE members ADD COLUMN task_filter text NOT NULL DEFAULT 'all' CHECK (task_filter IN ('all', 'indoor', 'outdoor'));
ALTER TABLE members ADD COLUMN task_show_completed boolean NOT NULL DEFAULT false;

-- migrate:down
ALTER TABLE members DROP COLUMN task_show_completed;
ALTER TABLE members DROP COLUMN task_filter;
ALTER TABLE members DROP COLUMN task_sort;
DROP TABLE care_actions;
ALTER TABLE care_events DROP COLUMN previous_pot;
ALTER TABLE care_events DROP COLUMN batch_id;
ALTER TABLE care_events DROP COLUMN event_order;
ALTER TABLE care_events DROP CONSTRAINT care_events_kind_check;
-- Refuse rollback with postponement history rather than deleting care records.
ALTER TABLE care_events ADD CONSTRAINT care_events_kind_check CHECK (kind IN ('completed'));

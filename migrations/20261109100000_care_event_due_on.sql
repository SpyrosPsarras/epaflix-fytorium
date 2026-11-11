-- migrate:up
ALTER TABLE care_events ADD COLUMN due_on date;

-- migrate:down
ALTER TABLE care_events DROP COLUMN due_on;

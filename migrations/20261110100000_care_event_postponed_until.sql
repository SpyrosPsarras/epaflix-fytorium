-- migrate:up
ALTER TABLE care_events ADD COLUMN postponed_until date;

-- migrate:down
ALTER TABLE care_events DROP COLUMN postponed_until;

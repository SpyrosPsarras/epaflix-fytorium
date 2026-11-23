-- migrate:up
ALTER TABLE identification_quota ADD COLUMN stored_on date;

-- migrate:down
ALTER TABLE identification_quota DROP COLUMN stored_on;

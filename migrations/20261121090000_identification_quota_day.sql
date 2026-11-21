-- migrate:up
ALTER TABLE identification_quota ADD COLUMN stored_on date NOT NULL DEFAULT CURRENT_DATE;

-- migrate:down
ALTER TABLE identification_quota DROP COLUMN stored_on;

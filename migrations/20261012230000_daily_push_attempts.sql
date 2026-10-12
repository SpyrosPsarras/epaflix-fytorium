-- migrate:up
ALTER TABLE daily_reminder_sends ADD COLUMN attempts integer NOT NULL DEFAULT 1 CHECK (attempts BETWEEN 1 AND 6);

-- migrate:down
ALTER TABLE daily_reminder_sends DROP COLUMN attempts;

-- migrate:up
ALTER TABLE completion_notice_sends ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 6);

-- migrate:down
ALTER TABLE completion_notice_sends DROP COLUMN attempts;

-- migrate:up
ALTER TABLE app_refresh_tokens ADD COLUMN first_used_at timestamptz;
ALTER TABLE app_refresh_tokens ADD COLUMN successor_hash text;
ALTER TABLE app_refresh_tokens ADD COLUMN grace_issues integer NOT NULL DEFAULT 0 CHECK (grace_issues BETWEEN 0 AND 5);
ALTER TABLE app_access_tokens ADD COLUMN refresh_hash text;
-- Existing pairs have no reliable use history, so do not allow grace for them.
UPDATE app_refresh_tokens SET first_used_at = COALESCE(used_at, CURRENT_TIMESTAMP);

-- migrate:down
ALTER TABLE app_access_tokens DROP COLUMN refresh_hash;
ALTER TABLE app_refresh_tokens DROP COLUMN grace_issues;
ALTER TABLE app_refresh_tokens DROP COLUMN successor_hash;
ALTER TABLE app_refresh_tokens DROP COLUMN first_used_at;

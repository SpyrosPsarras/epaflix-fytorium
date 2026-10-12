-- migrate:up
ALTER TABLE weather_alert_sends ADD COLUMN attempts integer NOT NULL DEFAULT 1 CHECK (attempts BETWEEN 1 AND 6);

-- migrate:down
ALTER TABLE weather_alert_sends DROP COLUMN attempts;

-- migrate:up
ALTER TABLE members
  ADD COLUMN feed_late_tasks boolean NOT NULL DEFAULT true,
  ADD COLUMN feed_current_weather boolean NOT NULL DEFAULT true,
  ADD COLUMN feed_weather_alerts boolean NOT NULL DEFAULT true,
  ADD COLUMN feed_plant_warnings boolean NOT NULL DEFAULT true;

-- migrate:down
ALTER TABLE members
  DROP COLUMN feed_late_tasks,
  DROP COLUMN feed_current_weather,
  DROP COLUMN feed_weather_alerts,
  DROP COLUMN feed_plant_warnings;

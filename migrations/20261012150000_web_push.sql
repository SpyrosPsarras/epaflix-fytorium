-- migrate:up
ALTER TABLE members
  ADD COLUMN reminder_time text NOT NULL DEFAULT '09:00' CHECK (reminder_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  ADD COLUMN reminders_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN weather_alerts_enabled boolean NOT NULL DEFAULT false;

CREATE TABLE push_subscriptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  endpoint text NOT NULL UNIQUE CHECK (endpoint LIKE 'https://%'),
  p256dh text NOT NULL,
  auth text NOT NULL,
  label text NOT NULL CHECK (length(label) BETWEEN 1 AND 200),
  created_at timestamptz NOT NULL,
  last_success_at timestamptz
);
CREATE INDEX push_subscriptions_member ON push_subscriptions(member_id);
CREATE TABLE daily_reminder_sends (
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  local_date date NOT NULL,
  attempted_at timestamptz NOT NULL,
  claimed_until timestamptz NOT NULL,
  sent_at timestamptz,
  PRIMARY KEY (member_id, local_date)
);
CREATE TABLE weather_alert_sends (
  event_id uuid NOT NULL REFERENCES weather_events(id) ON DELETE CASCADE,
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  attempted_at timestamptz NOT NULL,
  claimed_until timestamptz NOT NULL,
  sent_at timestamptz,
  PRIMARY KEY (event_id, member_id)
);

-- migrate:down
DROP TABLE weather_alert_sends;
DROP TABLE daily_reminder_sends;
DROP TABLE push_subscriptions;
ALTER TABLE members DROP COLUMN weather_alerts_enabled, DROP COLUMN reminders_enabled, DROP COLUMN reminder_time;

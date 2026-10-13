-- migrate:up
ALTER TABLE login_transactions ADD COLUMN app_challenge text;
ALTER TABLE login_transactions ADD COLUMN app_state text;
ALTER TABLE login_transactions ADD CONSTRAINT app_login_parameters CHECK ((app_challenge IS NULL) = (app_state IS NULL));

CREATE TABLE app_login_codes (
  code_hash text PRIMARY KEY,
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  challenge text NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE INDEX app_login_codes_expiry ON app_login_codes(expires_at);

CREATE TABLE app_devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  created_at timestamptz NOT NULL,
  last_used_at timestamptz NOT NULL,
  revoked_at timestamptz
);
CREATE INDEX app_devices_member ON app_devices(member_id);

CREATE TABLE app_access_tokens (
  token_hash text PRIMARY KEY,
  device_id uuid NOT NULL REFERENCES app_devices(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL
);
CREATE INDEX app_access_tokens_device ON app_access_tokens(device_id);
CREATE INDEX app_access_tokens_expiry ON app_access_tokens(expires_at);
CREATE TABLE app_refresh_tokens (
  token_hash text PRIMARY KEY,
  device_id uuid NOT NULL REFERENCES app_devices(id) ON DELETE CASCADE,
  used_at timestamptz
);
CREATE INDEX app_refresh_tokens_device ON app_refresh_tokens(device_id);
CREATE INDEX app_refresh_tokens_used ON app_refresh_tokens(used_at) WHERE used_at IS NOT NULL;

-- migrate:down
DROP TABLE app_refresh_tokens;
DROP TABLE app_access_tokens;
DROP TABLE app_devices;
DROP TABLE app_login_codes;
ALTER TABLE login_transactions DROP CONSTRAINT app_login_parameters;
ALTER TABLE login_transactions DROP COLUMN app_state;
ALTER TABLE login_transactions DROP COLUMN app_challenge;

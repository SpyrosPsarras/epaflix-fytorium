-- migrate:up
ALTER TABLE members ADD COLUMN removed_at timestamptz,
  ADD COLUMN completion_notices_enabled boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT members_household_identity UNIQUE (id, household_id);
ALTER TABLE plants ADD CONSTRAINT plants_household_identity UNIQUE (id, household_id);
CREATE TABLE plant_shares (
  household_id uuid NOT NULL REFERENCES households(id),
  member_id uuid NOT NULL,
  plant_id uuid NOT NULL,
  PRIMARY KEY (member_id, plant_id),
  FOREIGN KEY (member_id, household_id) REFERENCES members(id, household_id) ON DELETE CASCADE,
  FOREIGN KEY (plant_id, household_id) REFERENCES plants(id, household_id) ON DELETE CASCADE
);
CREATE TABLE household_invites (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES households(id),
  token_hash text NOT NULL UNIQUE,
  role text NOT NULL CHECK (role IN ('family', 'sitter')),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  consumed_at timestamptz,
  UNIQUE (id, household_id)
);
CREATE TABLE invite_plants (
  invite_id uuid NOT NULL,
  household_id uuid NOT NULL,
  plant_id uuid NOT NULL,
  PRIMARY KEY (invite_id, plant_id),
  FOREIGN KEY (invite_id, household_id) REFERENCES household_invites(id, household_id) ON DELETE CASCADE,
  FOREIGN KEY (plant_id, household_id) REFERENCES plants(id, household_id) ON DELETE CASCADE
);
CREATE TABLE completion_notice_sends (
  event_id uuid NOT NULL REFERENCES care_events(id) ON DELETE CASCADE,
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  attempted_at timestamptz NOT NULL,
  claimed_until timestamptz NOT NULL,
  sent_at timestamptz,
  PRIMARY KEY (event_id, member_id)
);
-- migrate:down
DROP TABLE completion_notice_sends;
DROP TABLE invite_plants;
DROP TABLE household_invites;
DROP TABLE plant_shares;
ALTER TABLE plants DROP CONSTRAINT plants_household_identity;
ALTER TABLE members DROP CONSTRAINT members_household_identity, DROP COLUMN completion_notices_enabled, DROP COLUMN removed_at;

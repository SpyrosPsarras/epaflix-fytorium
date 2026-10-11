-- migrate:up
CREATE TABLE households (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL DEFAULT 'My household'
);
CREATE UNIQUE INDEX households_singleton ON households ((true));

CREATE TABLE members (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES households(id),
  issuer text NOT NULL,
  subject text NOT NULL,
  role text NOT NULL CHECK (role IN ('owner', 'family', 'sitter')),
  UNIQUE (issuer, subject)
);

CREATE TABLE sessions (
  token_hash text PRIMARY KEY,
  member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_expiry ON sessions(expires_at);

CREATE TABLE login_transactions (
  token_hash text PRIMARY KEY,
  state text NOT NULL,
  nonce text NOT NULL,
  verifier text NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE INDEX login_transactions_expiry ON login_transactions(expires_at);

-- migrate:down
DROP TABLE login_transactions;
DROP TABLE sessions;
DROP TABLE members;
DROP TABLE households;

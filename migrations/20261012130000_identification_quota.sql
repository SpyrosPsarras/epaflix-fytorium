-- migrate:up
CREATE TABLE identification_quota (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  remaining integer NOT NULL CHECK (remaining >= 0)
);

-- migrate:down
DROP TABLE identification_quota;

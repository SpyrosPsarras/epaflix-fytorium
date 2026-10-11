-- migrate:up
CREATE TABLE sites (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES households(id),
  name text NOT NULL CHECK (btrim(name) <> ''),
  location text NOT NULL CHECK (location IN ('indoor', 'outdoor')),
  light text NOT NULL CHECK (light IN ('full_shade', 'part_shade', 'part_sun', 'full_sun')),
  grow_light boolean NOT NULL,
  warm_min_c integer,
  warm_max_c integer,
  cold_min_c integer,
  cold_max_c integer,
  humidity text NOT NULL CHECK (humidity IN ('dry', 'normal', 'humid')),
  draft boolean NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (warm_min_c <= warm_max_c),
  CHECK (cold_min_c <= cold_max_c),
  UNIQUE (id, household_id)
);
CREATE INDEX sites_household ON sites(household_id);

-- Plants have no API yet. Ticket #5 extends these rows with species and care data.
CREATE TABLE plants (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES households(id),
  site_id uuid NOT NULL,
  nickname text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (site_id, household_id) REFERENCES sites(id, household_id) ON DELETE RESTRICT
);
CREATE INDEX plants_site ON plants(site_id, household_id);

-- migrate:down
DROP TABLE plants;
DROP TABLE sites;

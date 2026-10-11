-- migrate:up
CREATE TABLE species (
  gbif_key integer PRIMARY KEY CHECK (gbif_key > 0),
  scientific_name text NOT NULL,
  canonical_name text NOT NULL,
  rank text NOT NULL,
  fetched_at timestamptz NOT NULL
);
ALTER TABLE plants
  ALTER COLUMN nickname DROP NOT NULL,
  ADD COLUMN gbif_key integer REFERENCES species(gbif_key),
  ADD COLUMN cultivar text,
  ADD COLUMN birthday date,
  ADD COLUMN attributes_as_of date NOT NULL DEFAULT CURRENT_DATE,
  ADD COLUMN pot_type text NOT NULL DEFAULT 'plastic' CHECK (pot_type IN ('plastic', 'terracotta', 'ceramic', 'glass', 'metal', 'wood', 'fabric', 'in_ground')),
  ADD COLUMN soil_type text NOT NULL DEFAULT 'all_purpose' CHECK (soil_type IN ('all_purpose', 'cactus', 'orchid', 'aroid', 'other')),
  ADD COLUMN plant_size text NOT NULL DEFAULT 'small' CHECK (plant_size IN ('small', 'medium', 'large', 'very_large')),
  ADD COLUMN growth_stage text NOT NULL DEFAULT 'young' CHECK (growth_stage IN ('seedling', 'young', 'mature')),
  ADD COLUMN fertilizer_type text NOT NULL DEFAULT 'liquid' CHECK (fertilizer_type IN ('liquid', 'sticks', 'slow_release')),
  ADD COLUMN pot_size_cm integer CHECK (pot_size_cm > 0),
  ADD COLUMN window_distance_cm integer CHECK (window_distance_cm > 0),
  ADD COLUMN drainage boolean NOT NULL DEFAULT false,
  ADD COLUMN grow_light boolean NOT NULL DEFAULT false,
  ADD COLUMN near_ac boolean NOT NULL DEFAULT false,
  ADD COLUMN near_heater boolean NOT NULL DEFAULT false,
  ADD CONSTRAINT plants_birthday_check CHECK (birthday <= attributes_as_of);
-- No real plant data exists yet. Remove the pre-species placeholders.
DELETE FROM plants WHERE gbif_key IS NULL;
ALTER TABLE plants ALTER COLUMN gbif_key SET NOT NULL;

-- migrate:down
ALTER TABLE plants DROP CONSTRAINT plants_birthday_check,
  DROP COLUMN gbif_key, DROP COLUMN cultivar, DROP COLUMN birthday, DROP COLUMN attributes_as_of,
  DROP COLUMN pot_type, DROP COLUMN soil_type, DROP COLUMN plant_size, DROP COLUMN growth_stage,
  DROP COLUMN fertilizer_type, DROP COLUMN pot_size_cm, DROP COLUMN window_distance_cm,
  DROP COLUMN drainage, DROP COLUMN grow_light, DROP COLUMN near_ac, DROP COLUMN near_heater;
UPDATE plants SET nickname = '' WHERE nickname IS NULL;
ALTER TABLE plants ALTER COLUMN nickname SET NOT NULL;
DROP TABLE species;

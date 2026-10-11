-- migrate:up
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
-- The unaccent dictionary is fixed for this index. Rebuild it if the dictionary changes.
CREATE FUNCTION normalise_species_name(text) RETURNS text
  LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
  AS $$ SELECT lower(public.unaccent('public.unaccent', normalize(btrim($1), NFC))) $$;
CREATE TABLE species_names (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  gbif_key integer NOT NULL REFERENCES species(gbif_key) ON DELETE CASCADE,
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
  normalised text GENERATED ALWAYS AS (normalise_species_name(name)) STORED,
  language text NOT NULL CHECK (language IN ('en', 'nb', 'el', 'la')),
  source text NOT NULL CHECK (source IN ('gbif', 'wikidata', 'nortaxa', 'owner')),
  UNIQUE (gbif_key, normalised, language, source)
);
CREATE INDEX species_names_trigram ON species_names USING gin (normalised gin_trgm_ops);
CREATE TABLE species_name_fetches (
  gbif_key integer NOT NULL REFERENCES species(gbif_key) ON DELETE CASCADE,
  source text NOT NULL CHECK (source IN ('gbif', 'wikidata', 'nortaxa')),
  completed boolean NOT NULL DEFAULT false,
  failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 8),
  retry_after timestamptz NOT NULL,
  version integer NOT NULL DEFAULT 1,
  PRIMARY KEY (gbif_key, source)
);
CREATE INDEX species_name_fetches_due ON species_name_fetches (retry_after) WHERE NOT completed AND failures < 8;
INSERT INTO species_name_fetches (gbif_key, source, retry_after)
  SELECT gbif_key, source, CURRENT_TIMESTAMP FROM species CROSS JOIN unnest(ARRAY['gbif', 'wikidata', 'nortaxa']) AS source;
INSERT INTO species_names (gbif_key, name, language, source)
  SELECT gbif_key, canonical_name, 'la', 'gbif' FROM species;

-- migrate:down
DROP TABLE species_name_fetches;
DROP TABLE species_names;
DROP FUNCTION normalise_species_name(text);

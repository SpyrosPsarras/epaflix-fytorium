-- migrate:up
CREATE TABLE species_common_names (
  gbif_key integer NOT NULL REFERENCES species(gbif_key) ON DELETE CASCADE,
  language text NOT NULL CHECK (language IN ('en', 'nb', 'el')),
  name text CHECK (length(btrim(name)) BETWEEN 1 AND 200),
  PRIMARY KEY (gbif_key, language)
);

CREATE FUNCTION species_common_name(gbif_key integer, language text) RETURNS text
  LANGUAGE sql STABLE
  AS $$
    SELECT coalesce(
      (SELECT n.name FROM species_common_names n WHERE n.gbif_key = $1 AND n.language = CASE WHEN $2 IN ('en', 'nb', 'el') THEN $2 ELSE 'en' END),
      (SELECT n.name FROM species_common_names n WHERE n.gbif_key = $1 AND n.language = 'en')
    )
  $$;

CREATE OR REPLACE FUNCTION plant_display_name(nickname text, gbif_key integer, language text) RETURNS text
  LANGUAGE sql STABLE
  AS $$
    SELECT coalesce(nullif(btrim($1), ''), species_common_name($2, $3),
      (SELECT s.canonical_name FROM species s WHERE s.gbif_key = $2))
  $$;

INSERT INTO species_names (gbif_key, name, language, source)
  SELECT n.gbif_key, btrim(part), n.language, n.source
  FROM species_names n CROSS JOIN LATERAL regexp_split_to_table(n.name, '[,;]') AS part
  WHERE n.name ~ '[,;]' AND btrim(part) <> ''
  ON CONFLICT DO NOTHING;
DELETE FROM species_names WHERE name ~ '[,;]';

-- migrate:down
CREATE OR REPLACE FUNCTION plant_display_name(nickname text, gbif_key integer, language text) RETURNS text
  LANGUAGE sql STABLE
  AS $$
    SELECT coalesce(nullif(btrim($1), ''), (
      SELECT n.name FROM species_names n
      WHERE n.gbif_key = $2 AND n.language IN (CASE WHEN $3 IN ('en', 'nb', 'el') THEN $3 ELSE 'en' END, 'en')
      ORDER BY (n.language = CASE WHEN $3 IN ('en', 'nb', 'el') THEN $3 ELSE 'en' END) DESC,
        CASE n.source WHEN 'owner' THEN 0 WHEN 'nortaxa' THEN 1 WHEN 'wikidata' THEN 2 WHEN 'gbif' THEN 3 END,
        n.name
      LIMIT 1
    ), (SELECT s.canonical_name FROM species s WHERE s.gbif_key = $2))
  $$;
DROP FUNCTION species_common_name(integer, text);
DROP TABLE species_common_names;

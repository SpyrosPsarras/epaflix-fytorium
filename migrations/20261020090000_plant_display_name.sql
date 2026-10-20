-- migrate:up
CREATE FUNCTION plant_display_name(nickname text, gbif_key integer, language text) RETURNS text
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

-- migrate:down
DROP FUNCTION plant_display_name(text, integer, text);

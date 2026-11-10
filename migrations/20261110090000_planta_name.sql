-- migrate:up
ALTER TABLE plants ADD COLUMN planta_name text;

-- Only a committed review shows the name the last import wrote ('' when Planta sent none). Without one it stays unknown.
UPDATE plants p SET planta_name = (SELECT coalesce(s.mapped->>'nickname', '') FROM import_staging s
    WHERE s.household_id = p.household_id AND s.planta_plant_id = p.planta_plant_id AND s.status = 'committed')
WHERE p.planta_plant_id IS NOT NULL;

-- migrate:down
ALTER TABLE plants DROP COLUMN planta_name;

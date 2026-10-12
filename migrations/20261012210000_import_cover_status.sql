-- migrate:up
ALTER TABLE import_staging ADD COLUMN cover_error text;
UPDATE import_staging SET plant_id = NULL
  WHERE plant_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM plants WHERE id = import_staging.plant_id);
ALTER TABLE import_staging ADD CONSTRAINT import_staging_plant_fk
  FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE SET NULL;

-- migrate:down
ALTER TABLE import_staging DROP CONSTRAINT import_staging_plant_fk;
ALTER TABLE import_staging DROP COLUMN cover_error;

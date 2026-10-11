-- migrate:up
ALTER TABLE plants DROP CONSTRAINT plants_pot_type_check;
ALTER TABLE plants ADD CONSTRAINT plants_pot_type_check CHECK (pot_type IN ('plastic','terracotta','ceramic','glass','metal','wood','fabric','in_ground','other'));
ALTER TABLE plants ADD COLUMN planta_plant_id text, ADD COLUMN planta_cover_imported boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX plants_planta_id ON plants(household_id, planta_plant_id);
ALTER TABLE sites ADD COLUMN imported_conditions_incomplete boolean NOT NULL DEFAULT false;
ALTER TABLE care_events ADD COLUMN source text NOT NULL DEFAULT 'fytorium' CHECK (source IN ('fytorium','planta'));
CREATE UNIQUE INDEX care_events_planta_action ON care_events(household_id, plant_id, task_type) WHERE source = 'planta';
ALTER TABLE photo_updates ADD COLUMN source text NOT NULL DEFAULT 'fytorium' CHECK (source IN ('fytorium','planta'));
CREATE TABLE import_staging (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES households(id),
  planta_plant_id text NOT NULL,
  mapped jsonb NOT NULL,
  candidates jsonb NOT NULL,
  selected jsonb,
  status text NOT NULL CHECK (status IN ('review','confirmed','skipped','committed')),
  plant_id uuid,
  UNIQUE (household_id, planta_plant_id)
);

-- migrate:down
DROP TABLE import_staging;
ALTER TABLE photo_updates DROP COLUMN source;
DROP INDEX care_events_planta_action;
ALTER TABLE care_events DROP COLUMN source;
ALTER TABLE sites DROP COLUMN imported_conditions_incomplete;
DROP INDEX plants_planta_id;
ALTER TABLE plants DROP COLUMN planta_plant_id, DROP COLUMN planta_cover_imported;
UPDATE plants SET pot_type = 'plastic' WHERE pot_type = 'other';
ALTER TABLE plants DROP CONSTRAINT plants_pot_type_check;
ALTER TABLE plants ADD CONSTRAINT plants_pot_type_check CHECK (pot_type IN ('plastic','terracotta','ceramic','glass','metal','wood','fabric','in_ground'));

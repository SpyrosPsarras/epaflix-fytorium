-- migrate:up
ALTER TABLE plants
  ADD COLUMN status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'graveyard')),
  ADD COLUMN graveyard_on date,
  ALTER COLUMN site_id DROP NOT NULL,
  ADD CONSTRAINT plants_active_site CHECK (status <> 'active' OR site_id IS NOT NULL),
  ADD CONSTRAINT plants_graveyard_date CHECK ((status = 'graveyard') = (graveyard_on IS NOT NULL));

CREATE TABLE plant_species_changes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id uuid NOT NULL,
  household_id uuid NOT NULL,
  previous_gbif_key integer NOT NULL REFERENCES species(gbif_key),
  previous_name text NOT NULL,
  next_gbif_key integer NOT NULL REFERENCES species(gbif_key),
  next_name text NOT NULL,
  changed_at timestamptz NOT NULL,
  by_member uuid REFERENCES members(id) ON DELETE SET NULL,
  by_name text NOT NULL,
  FOREIGN KEY (plant_id, household_id) REFERENCES plants(id, household_id) ON DELETE CASCADE
);
CREATE INDEX plant_species_changes_history ON plant_species_changes(household_id, plant_id, changed_at DESC);

-- migrate:down
DROP TABLE plant_species_changes;
-- Refuse rollback if detached graveyard plants exist rather than deleting them.
ALTER TABLE plants ALTER COLUMN site_id SET NOT NULL;
ALTER TABLE plants DROP CONSTRAINT plants_active_site, DROP CONSTRAINT plants_graveyard_date,
  DROP COLUMN status, DROP COLUMN graveyard_on;

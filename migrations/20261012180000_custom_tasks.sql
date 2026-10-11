-- migrate:up
CREATE TABLE custom_tasks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES households(id),
  name text NOT NULL CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 60),
  interval_days integer NOT NULL CHECK (interval_days BETWEEN 1 AND 365),
  plant_id uuid,
  site_id uuid,
  created_at timestamptz NOT NULL,
  deleted_at timestamptz,
  CHECK ((plant_id IS NOT NULL)::integer + (site_id IS NOT NULL)::integer = 1),
  UNIQUE (id, household_id)
);
-- Target IDs survive target deletion so soft-deleted site tasks keep graveyard history.
-- Validate and lock targets on writes instead of cascading away those historical names.
CREATE FUNCTION check_custom_task_target() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.plant_id IS NOT NULL THEN
    PERFORM id FROM plants WHERE id = NEW.plant_id AND household_id = NEW.household_id FOR KEY SHARE;
  ELSE
    PERFORM id FROM sites WHERE id = NEW.site_id AND household_id = NEW.household_id FOR KEY SHARE;
  END IF;
  IF NOT FOUND THEN RAISE EXCEPTION 'Custom task target must belong to its household' USING ERRCODE = '23503'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER custom_task_target BEFORE INSERT OR UPDATE OF plant_id, site_id, household_id ON custom_tasks FOR EACH ROW EXECUTE FUNCTION check_custom_task_target();
CREATE FUNCTION retire_target_custom_tasks() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME = 'plants' THEN
    UPDATE custom_tasks SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP) WHERE plant_id = OLD.id AND household_id = OLD.household_id;
  ELSE
    UPDATE custom_tasks SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP) WHERE site_id = OLD.id AND household_id = OLD.household_id;
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER retire_plant_custom_tasks BEFORE DELETE ON plants FOR EACH ROW EXECUTE FUNCTION retire_target_custom_tasks();
CREATE TRIGGER retire_site_custom_tasks BEFORE DELETE ON sites FOR EACH ROW EXECUTE FUNCTION retire_target_custom_tasks();
CREATE INDEX custom_tasks_active ON custom_tasks(household_id) WHERE deleted_at IS NULL;
ALTER TABLE care_events ALTER COLUMN task_type DROP NOT NULL;
ALTER TABLE care_events ADD COLUMN custom_task_id uuid;
ALTER TABLE care_events ADD CONSTRAINT care_events_task_identity CHECK ((task_type IS NOT NULL)::integer + (custom_task_id IS NOT NULL)::integer = 1);
ALTER TABLE care_events ADD CONSTRAINT care_events_custom_task_household FOREIGN KEY (custom_task_id, household_id) REFERENCES custom_tasks(id, household_id);

-- migrate:down
-- Refuse rollback while custom history exists rather than deleting it.
ALTER TABLE care_events ALTER COLUMN task_type SET NOT NULL;
ALTER TABLE care_events DROP CONSTRAINT care_events_custom_task_household;
ALTER TABLE care_events DROP CONSTRAINT care_events_task_identity;
ALTER TABLE care_events DROP COLUMN custom_task_id;
DROP TRIGGER retire_plant_custom_tasks ON plants;
DROP TRIGGER retire_site_custom_tasks ON sites;
DROP FUNCTION retire_target_custom_tasks();
DROP TABLE custom_tasks;
DROP FUNCTION check_custom_task_target();

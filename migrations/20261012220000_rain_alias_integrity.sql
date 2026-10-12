-- migrate:up
-- Keep writes out between the existing-row check and constraint installation.
LOCK TABLE care_actions IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM care_actions alias LEFT JOIN care_actions target
      ON target.request_id = alias.batch_id AND target.household_id = alias.household_id
    WHERE alias.batch_id IS NOT NULL
      AND (target.request_id IS NULL OR target.action <> 'rain' OR target.batch_id IS NOT NULL)
  ) THEN
    RAISE EXCEPTION 'Existing rain aliases must reference original rain actions in their household'
      USING ERRCODE = '23514', CONSTRAINT = 'care_actions_rain_alias_target',
        HINT = 'Find offending rows with: SELECT alias.* FROM care_actions alias LEFT JOIN care_actions target ON target.request_id = alias.batch_id AND target.household_id = alias.household_id WHERE alias.batch_id IS NOT NULL AND (target.request_id IS NULL OR target.action <> ''rain'' OR target.batch_id IS NOT NULL);';
  END IF;
END;
$$;
ALTER TABLE care_actions
  ADD COLUMN is_original boolean GENERATED ALWAYS AS (batch_id IS NULL) STORED,
  ADD COLUMN alias_target_action text GENERATED ALWAYS AS (CASE WHEN batch_id IS NOT NULL THEN 'rain' END) STORED,
  ADD COLUMN alias_target_original boolean GENERATED ALWAYS AS (CASE WHEN batch_id IS NOT NULL THEN true END) STORED,
  ADD CONSTRAINT care_actions_original_target UNIQUE (request_id, household_id, action, is_original),
  ADD CONSTRAINT care_actions_rain_alias_target
    FOREIGN KEY (batch_id, household_id, alias_target_action, alias_target_original)
    REFERENCES care_actions (request_id, household_id, action, is_original) MATCH SIMPLE;
CREATE INDEX care_actions_alias_batch_household ON care_actions (batch_id, household_id) WHERE batch_id IS NOT NULL;

-- migrate:down
DROP INDEX care_actions_alias_batch_household;
ALTER TABLE care_actions
  DROP CONSTRAINT care_actions_rain_alias_target,
  DROP CONSTRAINT care_actions_original_target,
  DROP COLUMN alias_target_original,
  DROP COLUMN alias_target_action,
  DROP COLUMN is_original;

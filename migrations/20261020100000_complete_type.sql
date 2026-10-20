-- migrate:up
ALTER TABLE care_actions DROP CONSTRAINT care_actions_action_plant,
  ADD CONSTRAINT care_actions_action_plant CHECK ((action IN ('rain', 'complete-type')) = (plant_id IS NULL));

-- migrate:down
-- Refuse rollback while complete-type actions remain, rather than deleting care history.
ALTER TABLE care_actions DROP CONSTRAINT care_actions_action_plant,
  ADD CONSTRAINT care_actions_action_plant CHECK ((action = 'rain') = (plant_id IS NULL));

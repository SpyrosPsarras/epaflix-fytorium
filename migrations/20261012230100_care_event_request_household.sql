-- migrate:up
ALTER TABLE care_events DROP CONSTRAINT care_events_request_id_key,
  ADD CONSTRAINT care_events_household_request_id_key UNIQUE (household_id, request_id);

-- migrate:down
ALTER TABLE care_events DROP CONSTRAINT care_events_household_request_id_key,
  ADD CONSTRAINT care_events_request_id_key UNIQUE (request_id);

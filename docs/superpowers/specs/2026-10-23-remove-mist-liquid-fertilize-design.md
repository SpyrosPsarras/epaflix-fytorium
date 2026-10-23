# Remove mist and make liquid fertilizing water plants

## Why

Remove mist reminders and humidity need from Fytorium. Completing liquid fertilizing must also restart the plant's water schedule, with linked undo and no duplicate watering in one action.

## Approved design

### D1. Remove mist completely

- Remove the `mist` task type and the `mist.*` rules from the engine, labels, Today, plant pages, OpenAPI, CONTEXT.md, README and the Android app (care models, plant forms, household screen).
- Remove both misting switches: the household and plant `misting_enabled` columns, their API fields, and the settings pages on web and Android.
- Planta import ignores Planta's misting dates.
- A new migration deletes mist care events, mist custom intervals and mist care actions. It also drops the `misting_enabled` columns and removes `mist` from the CHECK constraints. This permanently deletes data (owner approved).

### D2. Remove humidity need

- Remove `humidityNeed` from the care profile, the Open Plantbook humidity mapping, overrides, the care card and Android species care.
- The migration deletes stored humidityNeed overrides and cached values.

### D3. Liquid fertilizer also waters

- If a plant's `fertilizer_type` is `liquid`, completing Fertilize also records a water completion at the same time, even when watering isn't due yet. The water schedule then starts again from today.
- This works for a single Done, the plant's Handle all and the Fertilize card's Handle all. No plant gets two water completions from one action.
- Undoing the fertilize completion also undoes the water it added, and so does undoing the batch. After a single Done, undoing water alone leaves fertilize in place, but water added by Handle all is undone with its batch and single-event undo returns 409.
- Snooze and skip on Fertilize don't touch water. Sticks and slow-release fertilizer don't water.
- After you complete Fertilize, web and Android show the new water date without a manual refresh.
- Each event sends its own completion notice, so members get both "watered" and "fertilized" notices.

## Implementation decisions

### D4. Migration

Use `migrations/20261023090000_remove_mist.sql` with dbmate up and down sections. Old migrations stay unchanged. Delete retired events, intervals and actions, remove the cached and overridden care field, remove staged Planta mist dates, drop both switches and narrow the task CHECK constraints. Add `care_events.linked_event_id uuid` with the composite foreign key `(linked_event_id, household_id) REFERENCES care_events(id, household_id) ON DELETE CASCADE` and the unique constraint `care_events_id_household_unique` on `(id, household_id)`. The added water event points at its fertilize event. Down restores the old columns and CHECK constraints and drops the link. Deleted data is not restored.

### D5. Shared completion path

`recordEvent` returns the event ID. A shared helper in `src/lib/server/tasks.ts` adds the linked water event inside the same transaction and care action. Single Done calls it only for completed fertilizing. Per-plant Handle all skips it when that batch already recorded water. Per-type Handle all calls it for fertilizing. Added water events share the batch ID when there is one. The existing request identity prevents duplicate events on retry.

Single-event undo marks the requested event and events linked to it with the same undo time and member in one transaction. Undoing water does not follow the link back to fertilize. Batch undo includes the added water. Both completions use the existing notification path.

### D6. Client refresh and import

Web Today invalidates loaded tasks after fertilizing or undoing fertilizing. The plant page already invalidates after Handle all and undo. Android reloads schedules and any open plant detail after a single fertilize Done, using its existing refresh paths. Planta's mapping no longer includes misting. External synthetic Planta payloads can retain that field to test that the import ignores it.

## Checks

Update affected unit, API, browser and Android tests. Cover single and batch liquid fertilizing, other fertilizer types, snooze and skip, linked undo in both directions, batch undo, schedule updates, completion notices and request replay. Check removed settings and task endpoints return 404. Run dependency installation, typecheck and unit tests locally. API, browser and Android checks need Docker or Java and remain CI checks when those runtimes are unavailable.

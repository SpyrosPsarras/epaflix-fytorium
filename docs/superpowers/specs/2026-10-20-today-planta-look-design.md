# Android Today: Planta look, common names, tap opens plant page

## Why

Spyros compared the Android Today screen with Planta's and wants Fytorium to look and behave like Planta. Three problems prompted this:

1. The late badge ("46d late") wraps onto two lines and covers most of the plant photo. Its text is not centred.
2. Rows show scientific names ("Fragaria × ananassa"). Most people know "Strawberry".
3. Tapping a row opens task details inside the list. In Planta it opens the plant's own page.

Success: Today on the phone reads like Planta's Today. Plants are easy to recognise by photo and common name. A tap takes you to the plant.

## Goals

### G1. Late badge

- One line, never wrapped: `7d late`, or `2mo late` from 60 days late (whole months, rounded down, 30 days per month). *My call.*
- A small pill overlapping the bottom edge of the circular photo, as in Planta. Most of the photo stays visible.
- Text centred horizontally and vertically in the pill.
- Keep the current rule: shown only for pending tasks with `lateDays > 0`.

### G2. Planta look for Today (Spyros: "all")

Reference: Planta's dark-mode Today screenshot.

- Screen title "Today" centred in the top bar. Keep the existing search and notifications icons. *My call.*
- Each task group stays a rounded card with a large bold title ("Clean", "Fertilize").
- No divider lines between rows.
- Row: circular photo with late pill, plant name (larger), site name under it in a muted colour, snooze icon, outlined completion circle.
- Snooze icon becomes a bell with a "z" (Material `NotificationsPaused` or nearest equivalent). *My call.*
- The whole app theme changes from orange to green, light and dark, matched to Planta's soft green. Completion circles and snooze icons on Today use it. (Spyros: whole app.)
- Remove the greeting, daily tip and summary line from the top of Today. Keep the Today/Upcoming switch above the cards. (Spyros.)
- Each card ends with a centred, filled, pill-shaped "Handle all tasks" button. (Spyros.) No instructions hint under it; Fytorium has no instruction content yet. (Spyros.)
- Upcoming uses the same row component, so it gets the same row look. *My call.*

### G2a. "Handle all tasks" per card (Spyros: complete the whole card, with one Undo)

- Completes every pending task in that card that is due or late today, across all its plants, as one batch. *Due-or-late rule is my call, matching the plant page's handle-all.*
- One Undo reverses the whole batch, through the existing task-batch undo, extended with a new batch action kind for a task type.
- New server endpoint for completing all due tasks of one task type (built-in type or custom task) in the household. It reuses `completeAll`'s per-task completion rules.
- Shown to owner and family only, like the existing handle-all. *My call.*
- Hidden when the card has no pending tasks.

### G3. Common names in the phone's language (Spyros: phone language)

- A plant's name everywhere in the app is: nickname if set, else a vernacular name in the phone's language, else English vernacular, else canonical name. Name languages available are `en`, `nb` and `el`. Any other phone language falls to English. *Fallback order is my call.*
- Among several names in one language, prefer the owner's name, then NorTaxa, Wikidata, GBIF. *My call.*
- Applies to Today, Upcoming, Plants list and plant page title. The plant page keeps the scientific name under the title.
- *My call on mechanism:* the server picks the name using the request's `Accept-Language` header, so there is one rule in one place. Android sends the phone's language. The web then also follows the browser language. This changes the Plant display-name rule in `CONTEXT.md`, which must be updated.
- Offline cache keeps the last name the server sent. A changed phone language shows after the next refresh.
- All SQL that builds `display_name` (plant select, delete confirmation, ordering, feed) uses one shared rule. Delete confirmation accepts the name shown to that member, plus nickname and canonical name. *My call.*
- Locale mapping `no` and `nn` to `nb`. *My call.*

### G4. Tap opens the plant page (Spyros: yes)

- Tapping the photo or name of a row opens that plant's existing detail page (`PlantDetailScreen`), with Back returning to Today at the same scroll position.
- The detail page shows, next to the tapped task, the actions the in-row panel used to offer: Skip, Repot with new pot and soil, and the "Based on" provenance. The existing role rules in `task.actions()` decide which actions appear.
- The expandable in-row panel is removed.
- Undo after completing stays on the row as today. *My call.*
- Per-plant "Handle all tasks for <plant>" moves off Today; the plant page already has "Handle all tasks".
- Sitters: their plant page currently shows no tasks. When a sitter opens it from Today, it shows the tapped task with the actions `task.actions()` allows them, such as Skip, so sitters keep Skip. *My call.*

## Non-goals

- No web UI changes beyond the name rule in G3. The web does not get the per-card button.
- No new name languages.
- No care instructions content.
- No change to scheduling, task rules or the API's task grouping. The only new API is G2a's batch endpoint.

## Anti-goals

- The photo must not be hidden by the badge.
- A plant must not show different names on different screens of the same app.
- Touch targets stay at least 48 dp, and every control keeps its accessibility label.

## Constraints

- Android: Kotlin + Compose, Material 3, the existing `TaskRow`, `TaskScreen`, `Theme.kt`, `PlantDetailScreen`.
- Build and tests run only in Docker through `./scripts/android`.
- Existing Compose tests and screenshot tests must be updated, not deleted.

## Left to the builder

- Exact sizes, spacing and green shade, matched to the Planta screenshot.
- How Today navigates into the Plants tab's detail page.
- Test design.
